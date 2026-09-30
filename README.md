# 本地支付入账与对账（payment-ledger-reconciliation）

一套**完全本地、零外部依赖**的支付入账与渠道对账能力：用本地模拟器生成支付通知与渠道流水，
用内存仓储 + 分段锁完成状态机、复式账本和对账，不连接任何真实支付渠道或数据库。

- Java 21 / Spring Boot 3.4.x，仅 `spring-boot-starter-web` 与 test 依赖
- 金额一律使用最小货币单位的 `long`（如分），杜绝浮点漂移
- 所有关键动作有结构化日志：通知受理/拒绝、状态迁移、过账、守恒校验、对账差异与处理结论

---

## 1. 状态流转

支付单状态机（`PaymentStatus`）：

```
            SUCCESS(成功通知)
          ┌──────────────────► SUCCESS ───────────────┐
CREATED ──┤                                        REFUND │ REVERSAL
          └──────────────────► FAILED（终态）          ▼
                                                   REFUNDED（终态）
                                                   REVERSED（终态）
```

合法迁移表（`PaymentService` 中 `ALLOWED`）：

| 当前状态 | 允许迁移到 |
| --- | --- |
| `CREATED` | `SUCCESS`、`FAILED` |
| `SUCCESS` | `REFUNDED`、`REVERSED` |
| `FAILED` / `REFUNDED` / `REVERSED` | 无（终态） |

任何其他跳转（如 `SUCCESS->SUCCESS`、`FAILED->SUCCESS`、`REFUNDED->REFUND`）一律拒绝，
原因码 **`ILLEGAL_TRANSITION`**。状态校验在**任何资金动作之前**完成，因此非法跳转不会留下任何账本分录。

通知类型 `NotificationType`：`SUCCESS`、`FAILURE`、`REFUND`、`REVERSAL`；
其中 `FAILURE` 只置状态、不产生资金分录（支付未成功，无资金移动）。

---

## 2. 去重与并发规则（“只生效一次”）

`NotificationDedupRepository` 以 `notificationId` 为全局幂等键，状态为 `PROCESSING / DONE`：

1. **权限校验**：回调必须携带与通知商户匹配的 `X-API-Key`，否则 `PERMISSION_DENIED`。
2. **已完成重放**：`notificationId` 已为 `DONE` → 幂等返回
   `accepted=true, duplicate=true, reason=DUPLICATE_NOTIFICATION`，不再入账。
3. **并发重复**：`putIfAbsent(PROCESSING)` 抢占，只有一个线程能进入处理；其余线程直接返回
   `accepted=false, duplicate=true`（在途去重）。
4. **不同通知号的重复语义**：渠道若用**新的 notificationId 重发成功**，状态机判定
   `SUCCESS->SUCCESS` 非法，返回 `ILLEGAL_TRANSITION`，不会二次入账。
5. **失败可重试**：被拒绝或处理异常时（尚未产生任何业务效果）会释放 `PROCESSING` 占位，
   修正后可用同一 `notificationId` 重试；一旦成功置 `DONE` 即永久幂等。

同一 `orderNo` 的业务处理在专用分段锁内完成（`PaymentOrderRepository.lockFor`），
锁内重读最新状态，先校验状态机、再原子过账、最后落状态；不同单号互不阻塞。

---

## 3. 回调校验顺序与可区分原因码

校验按固定顺序执行，返回**最早失败**的明确原因码（`RejectReason`）：

| 顺序 | 校验内容 | 失败原因码 |
| --- | --- | --- |
| 0 | API Key 与商户匹配（权限） | `PERMISSION_DENIED` |
| 1 | 支付单存在 | `ORDER_NOT_FOUND` |
| 2 | 通知商户 == 单据商户 | `MERCHANT_MISMATCH` |
| 3 | 通知渠道 == 单据渠道且商户已授权 | `SOURCE_MISMATCH` |
| 4 | 通知金额 == 单据金额 | `AMOUNT_MISMATCH` |
| 5 | 通知币种 == 单据币种 | `CURRENCY_MISMATCH` |
| 6 | HMAC-SHA256 签名（常量时间比较） | `SIGNATURE_INVALID` |
| 7 | 状态机允许迁移 | `ILLEGAL_TRANSITION` |
| 8 | 账本原子过账 | `LEDGER_POSTING_FAILED` |

> 金额/币种校验先于签名：业务字段不一致与“伪造签名”是不同的失败原因，必须可区分。
> 本地签名原文为 `notificationId|orderNo|merchantId|channel|type|amount|currency`，
> 密钥来自本地商户目录 `MerchantDirectory`。

拒绝结果通过 HTTP 200 的 JSON body 返回（`accepted=false` + `reason`）；
建单等入口的参数/权限错误由 `GlobalExceptionHandler` 返回 HTTP 400 + 原因码。

---

## 4. 账本一致性（复式记账）

- 账户：`MERCHANT:{merchantId}:{currency}`（商户应收，贷方为正）与
  `CLEARING:{channel}:{currency}`（渠道清算，借方为正）；不同币种账户相互独立。
- 每个业务事件产生**方向相反、金额相等的一对不可变分录** `LedgerEntry`：
  - `SUCCESS`：借 渠道清算 / 贷 商户应收
  - `REFUND`、`REVERSAL`：借 商户应收 / 贷 渠道清算（红冲，净额回零）
  - `FAILURE`：无分录
- `LedgerRepository.postEntries` 在单把账本锁内执行：锁外先校验整批按币种借贷平衡，
  锁内先在临时增量表汇总净变化，全部计算完成后一次性提交余额并追加分录——
  **要么全部成功，要么完全不动**，不存在半落账。
- 故障注入 `FaultInjector`（测试用）让下一次过账在写入前抛异常，验证无余额漂移、无残留分录。
- 守恒校验 `GET /api/ledger/conservation`：
  1) 每个币种全部账户余额之和必须为 0；
  2) 每个 `notificationId` 必须恰好有两笔等金额、跨两个账户的借贷分录。
- 每笔分录带 `orderNo + notificationId + postedAt`，可沿单据与通知双向追溯。

---

## 5. 渠道对账与差异判定

渠道流水可通过 JSON 列表或 CSV 导入
（表头：`orderNo,merchantId,channel,amount,currency,status,occurredAt`，示例见
`src/test/resources/sample-data/channel-statement.csv`）。

差异类型 `DiscrepancyType`：

| 类型 | 判定规则 |
| --- | --- |
| `LOCAL_MISSING`（漏单） | 渠道流水有，本地无支付单 |
| `CHANNEL_MISSING`（多单） | 本地有支付单，渠道流水无 |
| `AMOUNT_MISMATCH` | 金额不一致（**优先**于状态判定）；币种不一致也归入此类并保留双方币种 |
| `STATUS_MISMATCH` | 金额相同但状态不等价 |
| `MATCHED` | 金额、币种、状态全部一致 |

渠道状态等价映射：`SUCCESS/PAID ↔ SUCCESS`、`FAIL/FAILED/CLOSED ↔ FAILED`、
`REFUND/REFUNDED ↔ REFUNDED`、`REVERSAL/REVERSED ↔ REVERSED`。

**可重复执行且结果稳定**：

- 每个 `batchId` 首次导入的流水会固化为快照（`ChannelDataSnapshotRepository`），
  之后重跑即使提交不同内容也一律基于首次快照重新计算，并返回相同数据哈希与差异结论。
- 同一批次的重跑在批次锁内串行执行。

**差异处理不静默覆盖历史**：

- 差异初始为 `OPEN`；人工处理后置 `HANDLED` 或 `IGNORED`，记录 `operator / note / handledAt`。
- 重跑时底层重新计算出 `OPEN`，若该差异已被处理，**保留历史处理结论与备注**，不回退为 OPEN。
- 对已处理差异再次处理默认拒绝（`DETAIL_ALREADY_HANDLED`）；
  显式 `force=true` 时也不覆盖，而是**追加**处理说明并标注“强制更新，历史已保留”。
- `MATCHED` 项不是差异，禁止当差异处理（`ILLEGAL_TRANSITION`）。

---

## 6. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/orders` | 建单（头 `X-API-Key`），body：orderNo/merchantId/channel/amount/currency |
| GET | `/api/orders/{orderNo}` | 查询支付单与状态 |
| POST | `/api/notifications` | 接收渠道通知（头 `X-API-Key`），返回 accepted/duplicate/reason |
| GET | `/api/ledger/accounts` | 全部账户余额 |
| GET | `/api/ledger/entries?orderNo=` | 分录（不带单号查全部） |
| GET | `/api/ledger/conservation` | 守恒校验结果 |
| POST | `/api/reconciliation/batches/{batchId}/run?channel=` | 用 JSON 流水执行/重跑对账 |
| POST | `/api/reconciliation/batches/{batchId}/run-csv?channel=` | 用 CSV 执行/重跑对账 |
| GET | `/api/reconciliation/batches` / `/{batchId}` | 批次列表 / 明细 |
| POST | `/api/reconciliation/batches/{batchId}/details/{detailId}/handle` | 处理差异：status/note/operator/force |

本地预置测试商户：`M001 / MOCK_CHANNEL / API Key=key-m001 / 密钥=secret-m001`
（由测试基类 `AbstractIntegrationTest` 注册）。

---

## 7. 本地验证方法

无需任何外部服务或数据库。

```bash
# 运行全部 31 个测试（离线模式）
mvn -o test

# 网络可用时也可直接
mvn test
```

测试覆盖（`src/test/java/.../tests`）：

| 测试类 | 覆盖点 |
| --- | --- |
| `NotificationFlowTest` | 正常成功/失败/退款/冲正流转；同号重放幂等；异号重复拒绝；20 线程并发通知只生效一次 |
| `NotificationRejectionTest` | 8 类可区分拒绝原因；权限失败；拒绝后修正可重试且无双重效果 |
| `LedgerConsistencyTest` | 多币种守恒、退款回零、注入过账异常后无半落账/无余额漂移、分录可追溯 |
| `ReconciliationTest` | 四类差异判定、重跑结果稳定（忽略后续不同输入）、已处理差异历史保护、强制更新留痕、批次/明细错误区分 |
| `CsvParserTest` | CSV 正常解析、空内容/坏表头/坏行列/坏数字的 `CSV_PARSE_ERROR` |
| `WebApiTest` | MockMvc 端到端：建单权限、成功回调、重放幂等、篡改签名、对账 MATCHED |

手工冒烟（启动后 `server.port=0` 为随机端口，可改为固定端口）：

```bash
mvn -o spring-boot:run -Dspring-boot.run.arguments=--server.port=8080

curl -s -X POST localhost:8080/api/orders -H 'X-API-Key: key-m001' \
  -H 'Content-Type: application/json' \
  -d '{"orderNo":"DEMO1","merchantId":"M001","channel":"MOCK_CHANNEL","amount":10000,"currency":"CNY"}'
# 通知由 LocalPaymentSimulator 以正确 HMAC 签名生成（测试中演示），再 POST 到 /api/notifications
curl -s localhost:8080/api/ledger/conservation
```

关键日志关键字（可在测试输出中直接观察流水与最终结果）：
`ORDER CREATED/TRANSITION`、`NOTIFY ACCEPTED/DUPLICATE/REJECTED`（含 reason）、
`LEDGER POSTED/POST FAILED/FAULT`、`CONSERVATION OK/FAIL`、
`RECON DONE/HANDLE`、`历史结论保护`。

---

## 8. 代码结构

```
domain/       枚举与领域模型（支付单、通知、渠道流水、账户、分录、对账批次/明细）
repo/         内存仓储：支付单分段锁、通知幂等表、复式账本、对账批次、流水快照
service/      状态机、通知处理链、账本过账与守恒、对账引擎、CSV 解析、商户目录、签名、故障注入
simulator/    本地渠道模拟器（正确签名通知 + 各类篡改构造器）
web/          REST 控制器与统一原因码异常处理
```
