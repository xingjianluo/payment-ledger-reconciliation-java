package com.github.xingjianluo.paymentledgerreconciliationjava.service;

import com.github.xingjianluo.paymentledgerreconciliationjava.domain.model.Account;
import com.github.xingjianluo.paymentledgerreconciliationjava.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 初始化本地模拟渠道密钥与两个资金账户（CNY/USD 各一个，初始余额 1_000_000） */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final SignatureService signatureService;
    private final AccountRepository accountRepository;

    public DataInitializer(SignatureService signatureService,
                           AccountRepository accountRepository) {
        this.signatureService = signatureService;
        this.accountRepository = accountRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        signatureService.registerChannel("MOCK_ALIPAY", "secret-alipay-local");
        signatureService.registerChannel("MOCK_WECHAT", "secret-wechat-local");

        createAccount("ACC-CNY-001", "CNY", 1_000_000L);
        createAccount("ACC-USD-001", "USD", 1_000_000L);
        log.info("local channels and accounts initialized");
    }

    private void createAccount(String id, String currency, long initialBalance) {
        if (accountRepository.findById(id).isEmpty()) {
            Account account = new Account();
            account.setAccountId(id);
            account.setCurrency(currency);
            account.setBalance(initialBalance);
            account.setVersion(0);
            accountRepository.save(account);
        }
    }
}
