package com.example;

/**
 * 储蓄账户：继承 Account 并实现 AccountService。
 */
public class SavingsAccount extends Account implements AccountService {

    private double interestRate;

    public SavingsAccount(double rate) {
        this.interestRate = rate;
    }

    public void applyInterest() {
        this.deposit(getBalance() * this.interestRate * 0.01);
    }

    @Override
    public void deposit(double amount) {
        super.deposit(amount);
    }

    @Override
    public double withdraw(double amount) {
        return super.withdraw(amount);
    }
}