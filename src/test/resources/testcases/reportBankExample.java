class BankAccount {
    int balance;

    // Constructor, Pure
    BankAccount(int initialBalance) {
        this.balance = initialBalance;
    }
    // Impure: modifies the state of the BankAccount
    void deposit(int amount) {
        this.balance += amount;
    }
}

class Wallet {
    BankAccount account;
    
    // Constructor, Pure
    Wallet(BankAccount account) {
        this.account = account;
    }

    // Impure: modifies the state of the BankAccount through the Wallet
    void addFunds(int amount) {
        this.account.balance += amount;
    }
}