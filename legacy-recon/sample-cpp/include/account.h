// 样例遗留 C++ 工程：银行账户（对齐 sample-java 语义）。
// 覆盖验证点：Namespace/Class/Struct/Method/Constructor/Destructor/Macro 实体、
// CONTAINS/INHERITS/CALLS/REFERENCES/DEPENDS_ON 关系、宏展开（R13）、模板策略（R12）。
#ifndef SAMPLE_ACCOUNT_H
#define SAMPLE_ACCOUNT_H

#include <string>

// 宏定义（R13：Macro 实体 + 展开点 REFERENCES）
#define ACCOUNT_API_VERSION 2
#define ACCOUNT_LOG(msg) do { log(__FILE__, msg); } while (0)

namespace banking {

// 枚举 + 枚举常量
enum class AccountStatus { ACTIVE, FROZEN, CLOSED };

// 基类：Class + Method + Constructor + Destructor + Field
class Account {
public:
    Account(std::string owner, double balance);
    virtual ~Account();

    virtual bool withdraw(double amount);
    double getBalance() const;
    const std::string& getOwner() const;
    static int getVersion();

protected:
    void log(const char* file, const char* msg);

    std::string owner_;
    double balance_;
    AccountStatus status_;
    static int version_;
};

// 派生类：INHERITS 关系
class SavingsAccount : public Account {
public:
    SavingsAccount(std::string owner, double balance, double rate);
    ~SavingsAccount() override;

    bool withdraw(double amount) override;
    void addInterest();

private:
    double rate_;
};

// 模板函数声明（R12：templatePolicy=declarations 时仅记录声明，实例化跳过）
template <typename T>
T clamp(T value, T lo, T hi);

// 自由函数（Function 实体）
double totalOf(const Account& a, const SavingsAccount& s);

} // namespace banking

#endif // SAMPLE_ACCOUNT_H
