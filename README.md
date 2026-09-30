# 本地支付入账与对账（payment-ledger-reconciliation-java）

纯本地实现的支付入账、账本与渠道对账能力：**不接真实支付渠道、不依赖任何外部服务**。
渠道通知由本地模拟器按订单生成（含 HmacSHA256 签名），渠道流水以 CSV 字符串导入，
数据存储全部为内存结构（`ConcurrentHashMap`），应用重启即重置。

- Java 21 + Spring Boot 3.4，仅 spring-web / spring-test 依赖
- 金额统一为最小币种单位的 `long`，不使用浮点数
- 关键链路均有结构化日志：通知ID、订单号、流水号、拒绝原因、最终状态/余额

## 1. 支付单状态流转

状态枚举：`CREATED -> SUCCESS / FAILED`，`SUCCESS -> REFUNDED / REVERSED`。

| 通知类型            | 允许的当前状态        | 目标状态   | 账本动作             |
|---------------------|-----------------------|------------|----------------------|
| `PAYMENT_SUCCESS`   | `CREATED`             | `SUCCESS`  | `+amount` 入账       |
| `PAYMENT_FAILED`    | `CREATED`             | `FAILED`   | 无流水               |
| `REFUND`            | `SUCCESS`             | `REFUNDED` | `-amount` 退款出账   |
| `REVERSAL`          | `SUCCESS`             | `REVERSED` | `-amount` 冲正出账（只允许对已入账成功的单冲正） |

其余跳转一律拒绝，原因码 `INVALID_STATUS_TRANSITION`。例如：
CREATED 直接退款、SUCCESS 再来成功通知、FAILED 单收到冲正、REFUNDED/REVERSED 上再做任何终态跳转。

## 2. 通知去重与并发规则（只能生效一次）

- 去重键为 `channelNotificationId`（渠道通知ID），同一ID：
  - 第一次合法处理 → `APPLIED`，订单流转、账本入账、写通知记录；
  - 再次到达且订单/渠道一致 → `IDEMPOTENT`，直接返回首次结论，不重复扣款、不重复流转；
  - 到达时绑定了**另一个订单或另一个渠道** → 拒绝，原因码 `DUPLICATE_NOTIFICATION`。
- 并发控制为三段固定顺序的分条锁：`notif:<通知ID> -> order:<订单ID> -> account:<账户ID>`。
  - 同一通知并发 32 次：恰好 1 个 `APPLIED`，其余全部 `IDEMPOTENT`（见并发测试）。
  - 成功/失败通知并发竞争同一 CREATED 订单：恰好 1 个生效，另一个被状态机拒绝。
  - 同一通知ID被两个订单并发使用：恰好 1 个生效，另一个以 `DUPLICATE_NOTIFICATION` 拒绝。
- 账本写入自身带幂等键 `通知类型:channelNotificationId`，配合账户锁内的
  “幂等检查 → 余额校验 → 写流水 → 改余额”原子序列，杜绝重复扣款。
- 例外：订单尚不存在（`ORDER_NOT_FOUND`）时不占用通知ID，订单稍后创建后同一通知仍可重试成功。

## 3. 来源与权限校验、可区分原因码

通知先做字段校验，再按以下顺序判定，原因码彼此可区分：

1. `INVALID_REQUEST`：缺字段、金额非正、未知通知类型；
2. `ORDER_NOT_FOUND`：订单不存在；
3. `SOURCE_MISMATCH`：渠道未注册、通知渠道 ≠ 订单渠道、商户号 ≠ 订单商户号；
4. `SIGNATURE_INVALID`：HmacSHA256 签名校验失败（常量时间比较）；
5. `AMOUNT_MISMATCH`：通知金额 ≠ 订单金额；
6. `CURRENCY_MISMATCH`：通知币种 ≠ 订单币种（或账户币种不符）；
7. `INVALID_STATUS_TRANSITION`：状态机不允许的跳转；
8. `LEDGER_POST_FAILED`：余额不足或入账链路故障。

签名串固定为 `channel|channelNotificationId|orderId|amount|currency|type`，
签名 = `HmacSHA256(渠道密钥, 签名串)` 的十六进制结果。本地渠道密钥在
`DataInitializer` 注册：`MOCK_ALIPAY -> secret-alipay-local`，
`MOCK_WECHAT -> secret-wechat-local`。

通知接口对业务拒绝返回 HTTP 200 + `{outcome:"REJECTED", rejectReason:...}`
（Webhook 语义：渠道看到成功投递，拒绝结论在响应体中）；
其余接口（建单、对账处理等）通过 `GlobalExceptionHandler` 返回 HTTP 422 与
`{reason, message}`。

## 4. 账本一致性

- 账户锁内原子完成余额校验与更新；出账后余额为负直接拒绝（`LEDGER_POST_FAILED`）。
- 入账、退款、冲正任一环节抛异常：不写流水、不改余额、订单状态不变，无部分落账。
- 流水只追加、不改不删；每条流水带幂等键，重复提交只生效一次。
- 守恒校验：`GET /api/ledger/report` 返回全部流水有符号金额合计 `sumSignedEntries`
  与各账户余额合计。恒等式：`sum(账户余额) - 初始注资 = sum(全部有符号流水)`。
  测试中断言“支付后全额退款 / 支付后冲正”时净额为 0、余额回到原点。
- `LedgerService#injectFailureForNextPost(orderId)` 为测试故障注入点，
  用于复现入账异常链路（失败 → 无漂移 → 重试成功）。

## 5. 渠道对账与差异判定

- 输入：`POST /api/reconcile/runs`，body 含 `channel`、`reconDate(yyyy-MM-dd)`、
  `csvLines`。CSV 列：
  `flowId,orderId,channel,merchantId,currency,amount,status,occurredAt(ISO-8601)`。
  渠道行的 channel 与请求渠道不一致直接拒绝（`SOURCE_MISMATCH`）。
- 匹配键为订单号 `orderId`，差异类型互斥、按优先级判定（金额 → 币种 → 状态）：

| 差异类型            | 含义                                   |
|---------------------|----------------------------------------|
| `MISSING_LOCAL`     | 漏单：渠道有流水，本地无订单            |
| `EXTRA_LOCAL`       | 多单：本地有终态订单，渠道无流水        |
| `AMOUNT_MISMATCH`   | 金额不一致                             |
| `CURRENCY_MISMATCH` | 币种不一致                             |
| `STATUS_MISMATCH`   | 状态不一致（SUCCESS/FAILED/REFUNDED/REVERSED 终态映射） |

- 差异稳定键：`channel:reconDate:identity`（identity 取 orderId，无订单时取 flowId）。
- **可重复执行且结果稳定**：同渠道同日重跑先替换当批导入流水，OPEN 差异集合完全由本次输入决定；
  `seenCount` 累加、`firstSeenAt` 保留、批次号每次新增（批次只追加）。
- 本次输入中已消失的 OPEN 差异标记为 `inactive`（不再出现在当前报告，历史记录仍可追溯）；
  **RESOLVED 差异永不被重跑重置或覆盖**。
- 差异处理：`POST /api/reconcile/diffs/{diffKey}/resolutions`，
  body `{action, comment, operator}`，处理结论（`ReconcileResolution`）只追加。
  对已处理差异再次处理返回 `DIFF_ALREADY_RESOLVED`，不允许静默改写历史结论；
  差异不存在或已失效返回 `DIFF_NOT_FOUND`。

## 6. HTTP 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/orders` | 建单（merchantId/accountId/channel/currency/amount） |
| GET  | `/api/orders/{orderId}` | 查单 |
| POST | `/api/notifications` | 接收模拟渠道通知 |
| GET  | `/api/ledger/report` | 余额与流水守恒汇总 |
| POST | `/api/reconcile/runs` | 导入CSV并执行对账 |
| GET  | `/api/reconcile/diffs?channel=&reconDate=&openOnly=` | 查差异 |
| POST | `/api/reconcile/diffs/{diffKey}/resolutions` | 处理差异（只追加结论） |

启动后内置账户：`ACC-CNY-001 (CNY, 1000000)`、`ACC-USD-001 (USD, 1000000)`。

## 7. 本地验证方法

无需数据库、无需网络，直接：

```bash
mvn test            # 全部 26 个测试，含并发/重跑/异常链路
mvn spring-boot:run # 启动后端口随机(application.properties: server.port=0)
```

### 手工 curl 验证

```bash
# 1) 建单
curl -s -X POST localhost:$PORT/api/orders -H 'Content-Type: application/json' -d '{
  "merchantId":"MCH-001","accountId":"ACC-CNY-001",
  "channel":"MOCK_ALIPAY","currency":"CNY","amount":1000}'
# 2) 成功通知的签名需用 LocalChannelSimulator 同规则计算，
#    测试代码中可直接调用 simulator.buildNotification(...) 获取带签名报文。
# 3) 对账（示例CSV）
curl -s -X POST localhost:$PORT/api/reconcile/runs -H 'Content-Type: application/json' -d '{
  "channel":"MOCK_ALIPAY","reconDate":"2026-09-30",
  "csvLines":["FLW-1,ORD-xxx,MOCK_ALIPAY,MCH-001,CNY,1000,SUCCESS,2026-09-30T10:00:00Z"]}'
```

### 测试覆盖（均为本地可复现数据）

- `NotificationServiceTest`：成功/退款流转、重复通知幂等、非法状态（含 FAILED 单冲正）、
  金额/币种/签名/来源/商户不匹配、订单不存在重试等 10 类原因码区分。
- `NotificationConcurrencyTest`：32 线程同通知并发、成功/失败竞争、跨订单复用通知ID。
- `LedgerConsistencyTest`：支付+退款+冲正守恒、幂等入账、故障注入无部分落账、
  通知遇账本报错后可重试、余额不足拒绝。
- `ReconcileServiceTest`：四类/五类差异识别、重跑稳定、消失差异 inactive 留痕、
  处理结论不可覆盖、未知差异/错误渠道来源的区分拒绝。
- `EndToEndIntegrationTest`：MockMvc 全链路（建单→验签失败→成功→重放幂等→
  非法跳转→退款→账本净额为0→对账重跑结果稳定）。

日志关键行示例：
`notification applied id=... orderId=... type=PAYMENT_SUCCESS newStatus=SUCCESS`、
`ledger posted ... signed=1000 balance=...`、
`notification rejected ... reason=AMOUNT_MISMATCH`、
`reconcile done batch=... diffs=N`、`diff resolved key=... action=...`。
