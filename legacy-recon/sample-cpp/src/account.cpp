// 样例遗留 C++ 工程实现文件：覆盖 CALLS、宏展开点、模板实例化调用、跨文件 INHERITS。
#include "account.h"

#include <cstdio>

namespace banking {

int Account::version_ = ACCOUNT_API_VERSION;

Account::Account(std::string owner, double balance)
        : owner_(std::move(owner)), balance_(balance), status_(AccountStatus::ACTIVE) {}

Account::~Account() = default;

bool Account::withdraw(double amount) {
    if (amount <= 0 || amount > balance_) {
        ACCOUNT_LOG("withdraw rejected"); // 宏展开点（R13 REFERENCES → 宏定义）
        return false;
    }
    balance_ -= amount;
    return true;
}

double Account::getBalance() const { return balance_; }

const std::string& Account::getOwner() const { return owner_; }

int Account::getVersion() { return version_; }

void Account::log(const char* file, const char* msg) {
    std::printf("[account] %s: %s\n", file, msg);
}

SavingsAccount::SavingsAccount(std::string owner, double balance, double rate)
        : Account(std::move(owner), balance), rate_(rate) {}

SavingsAccount::~SavingsAccount() = default;

bool SavingsAccount::withdraw(double amount) {
    // 调用基类方法（CALLS）+ 模板实例化调用（R12 策略）
    double fee = clamp(amount * rate_, 0.0, 10.0);
    return Account::withdraw(amount + fee);
}

void SavingsAccount::addInterest() {
    double interest = getBalance() * rate_;
    if (interest > 0) {
        withdraw(interest); // CALLS
    }
}

// 模板定义（templatePolicy=declarations 时声明已记录；此处实例化由调用方触发）
template <typename T>
T clamp(T value, T lo, T hi) {
    return value < lo ? lo : (value > hi ? hi : value);
}

// 显式实例化（whitelist/full 策略时记录实例化体）
template double clamp<double>(double, double, double);

double totalOf(const Account& a, const SavingsAccount& s) {
    return a.getBalance() + s.getBalance(); // CALLS（const 成员函数）
}

} // namespace banking
