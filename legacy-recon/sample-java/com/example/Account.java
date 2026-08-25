package com.example;

/**
 * 账户领域对象。余额 double，支持存取款。
 */
public class Account {
    private double balance;

    /** 存入金额，必须为正。 */
    public void deposit(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount <= 0");
        }
        this.balance += amount;
    }

    /** 取出金额，余额不足抛异常。 */
    public double withdraw(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount <= 0");
        }
        if (amount > this.balance) {
            throw new IllegalStateException("insufficient balance");
        }
        this.balance -= amount;
        return amount;
    }

    public double getBalance() {
        return this.balance;
    }
}
