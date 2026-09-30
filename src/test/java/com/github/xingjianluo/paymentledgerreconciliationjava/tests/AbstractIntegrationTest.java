package com.github.xingjianluo.paymentledgerreconciliationjava.tests;

import com.github.xingjianluo.paymentledgerreconciliationjava.repo.ChannelDataSnapshotRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.repo.LedgerRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.repo.NotificationDedupRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.repo.PaymentOrderRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.repo.ReconciliationRepository;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.FaultInjector;
import com.github.xingjianluo.paymentledgerreconciliationjava.service.MerchantDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 集成测试基类：每个用例前重置内存状态并登记本地测试商户。 */
@SpringBootTest(properties = "spring.main.banner-mode=off")
public abstract class AbstractIntegrationTest {

    protected static final String MERCHANT = "M001";
    protected static final String OTHER_MERCHANT = "M002";
    protected static final String CHANNEL = "MOCK_CHANNEL";
    protected static final String API_KEY = "key-m001";
    protected static final String OTHER_API_KEY = "key-m002";
    protected static final String SECRET = "secret-m001";
    protected static final String OTHER_SECRET = "secret-m002";

    @Autowired protected PaymentOrderRepository orderRepository;
    @Autowired protected NotificationDedupRepository dedupRepository;
    @Autowired protected LedgerRepository ledgerRepository;
    @Autowired protected ReconciliationRepository reconciliationRepository;
    @Autowired protected ChannelDataSnapshotRepository snapshotRepository;
    @Autowired protected MerchantDirectory merchantDirectory;
    @Autowired protected FaultInjector faultInjector;

    @BeforeEach
    void resetState() {
        orderRepository.reset();
        dedupRepository.reset();
        ledgerRepository.reset();
        reconciliationRepository.reset();
        snapshotRepository.reset();
        faultInjector.reset();
        merchantDirectory.registerMerchant(MERCHANT, CHANNEL, SECRET, API_KEY);
        merchantDirectory.registerMerchant(OTHER_MERCHANT, CHANNEL, OTHER_SECRET, OTHER_API_KEY);
    }
}
