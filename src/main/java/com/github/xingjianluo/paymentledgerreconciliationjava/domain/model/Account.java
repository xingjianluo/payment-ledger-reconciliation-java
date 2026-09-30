package com.github.xingjianluo.paymentledgerreconciliationjava.domain.model;

/** 资金账户，余额为最小币种单位的整数 */
public class Account {
    private String accountId;
    private String currency;
    private long balance;
    private long version;

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public long getBalance() { return balance; }
    public void setBalance(long balance) { this.balance = balance; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
