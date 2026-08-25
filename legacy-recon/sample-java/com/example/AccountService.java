package com.example;

/** 账户服务接口。 */
public interface AccountService {
    void deposit(double amount);

    double withdraw(double amount);
}