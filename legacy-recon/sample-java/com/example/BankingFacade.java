package com.example;

import java.math.BigDecimal;

/** 银行业务门面：串联账户操作与状态迁移。 */
public class BankingFacade {

    private final Account account;

    public BankingFacade(Account account) {
        this.account = account;
    }

    public void credit(BigDecimal amount) {
        this.account.deposit(amount.doubleValue());
    }

    public BigDecimal balance() {
        return BigDecimal.valueOf(this.account.getBalance());
    }
}