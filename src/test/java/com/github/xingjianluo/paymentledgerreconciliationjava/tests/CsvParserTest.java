package com.github.xingjianluo.paymentledgerreconciliationjava.tests;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.ChannelTransaction;
import com.github.xingjianluo.paymentledgerreconciliationjava.domain.RejectReason;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.ChannelDataParser;
import com.github.xingjianluo.paymentledgerreconciliationjava.support.PaymentBusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 本地渠道对账 CSV 解析。 */
class CsvParserTest extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired private ChannelDataParser parser;

    private static final String HEADER =
            "orderNo,merchantId,channel,amount,currency,status,occurredAt\n";

    @Test
    void parses_valid_rows() {
        String csv = HEADER
                + "O-1,M001,MOCK_CHANNEL,1000,CNY,SUCCESS,1700000000000\n"
                + "O-2,M001,MOCK_CHANNEL,2000,CNY,REFUND,\n";
        List<ChannelTransaction> rows = parser.parseCsv(csv);
        assertEquals(2, rows.size());
        assertEquals("O-1", rows.get(0).getOrderNo());
        assertEquals(1000L, rows.get(0).getAmount());
        assertEquals(0L, rows.get(1).getOccurredAt(), "空时间戳应为0");
    }

    @Test
    void blank_and_bad_header_are_csv_parse_errors() {
        PaymentBusinessException e1 = assertThrows(PaymentBusinessException.class,
                () -> parser.parseCsv(""));
        assertEquals(RejectReason.CSV_PARSE_ERROR, e1.getReason());

        PaymentBusinessException e2 = assertThrows(PaymentBusinessException.class,
                () -> parser.parseCsv("foo,bar\n1,2"));
        assertEquals(RejectReason.CSV_PARSE_ERROR, e2.getReason());
    }

    @Test
    void malformed_row_and_bad_number_are_csv_parse_errors_with_position() {
        String badColumns = HEADER + "O-1,M001,MOCK_CHANNEL,1000\n";
        PaymentBusinessException e1 = assertThrows(PaymentBusinessException.class,
                () -> parser.parseCsv(badColumns));
        assertEquals(RejectReason.CSV_PARSE_ERROR, e1.getReason());
        assertTrue(e1.getMessage().contains("第 2 行"));

        String badNumber = HEADER + "O-1,M001,MOCK_CHANNEL,abc,CNY,SUCCESS,1\n";
        PaymentBusinessException e2 = assertThrows(PaymentBusinessException.class,
                () -> parser.parseCsv(badNumber));
        assertEquals(RejectReason.CSV_PARSE_ERROR, e2.getReason());
        assertTrue(e2.getMessage().contains("数字字段非法"));
    }
}
