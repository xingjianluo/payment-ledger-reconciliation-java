package com.github.xingjianluo.paymentledgerreconciliationjava.domain;

/** 账本账户（商户应收 / 渠道清算）。余额以借贷方向累计，最小货币单位。 */
public class LedgerAccount {
    private String accountId;
    private String merchantId;
    private String currency;
    private long balance;
    private long version;

    public LedgerAccount() {
    }

    public LedgerAccount(String accountId, String merchantId, String currency) {
        this.accountId = accountId;
        this.merchantId = merchantId;
        this.currency = currency;
    }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public long getBalance() { return balance; }
    public void setBalance(long balance) { this.balance = balance; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
