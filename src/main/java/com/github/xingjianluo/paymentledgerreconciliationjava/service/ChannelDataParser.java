package com.github.xingjianluo.paymentledgerreconciliationjava.service;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.ChannelTransaction;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.RejectReason;
import com.github.xingjianluo.paymentledgerreconciliationjava.support.PaymentBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地对账文件解析（CSV），不访问外部渠道。
 * 表头：orderNo,merchantId,channel,amount,currency,status,occurredAt
 */
@Service
public class ChannelDataParser {

    private static final Logger log = LoggerFactory.getLogger(ChannelDataParser.class);

    public List<ChannelTransaction> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            throw new PaymentBusinessException(RejectReason.CSV_PARSE_ERROR, "CSV 内容为空");
        }
        String[] lines = csv.strip().split("\\r?\\n");
        if (lines.length < 2) {
            throw new PaymentBusinessException(RejectReason.CSV_PARSE_ERROR, "CSV 缺少表头或数据行");
        }
        String[] header = lines[0].split(",");
        if (header.length < 7 || !"orderNo".equals(header[0].trim())) {
            throw new PaymentBusinessException(RejectReason.CSV_PARSE_ERROR,
                    "CSV 表头非法，应为 orderNo,merchantId,channel,amount,currency,status,occurredAt");
        }
        List<ChannelTransaction> result = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] p = line.split(",", -1);
            if (p.length < 7) {
                throw new PaymentBusinessException(RejectReason.CSV_PARSE_ERROR,
                        "第 " + (i + 1) + " 行列数不足: " + line);
            }
            try {
                ChannelTransaction t = new ChannelTransaction();
                t.setOrderNo(p[0].trim());
                t.setMerchantId(p[1].trim());
                t.setChannel(p[2].trim());
                t.setAmount(Long.parseLong(p[3].trim()));
                t.setCurrency(p[4].trim());
                t.setStatus(p[5].trim());
                t.setOccurredAt(p[6].trim().isEmpty() ? 0L : Long.parseLong(p[6].trim()));
                if (t.getAmount() <= 0) {
                    throw new NumberFormatException("金额必须为正数");
                }
                result.add(t);
            } catch (NumberFormatException ex) {
                throw new PaymentBusinessException(RejectReason.CSV_PARSE_ERROR,
                        "第 " + (i + 1) + " 行数字字段非法: " + line + " (" + ex.getMessage() + ")");
            }
        }
        log.info("CSV PARSED rows={}", result.size());
        return result;
    }
}
