class BankAccount {
    int balance;

    // Constructor, Side-effect-free
    BankAccount(int initialBalance) {
        this.balance = initialBalance;
    }
    // Side-effecting: modifies the state of the BankAccount
    void deposit(int amount) {
        this.balance += amount;
    }
}

class Wallet {
    BankAccount account;
    
    // Constructor, Side-effect-free
    Wallet(BankAccount account) {
        this.account = account;
    }

    // Side-effecting: modifies the state of the BankAccount through the Wallet
    void addFunds(int amount) {
        this.account.balance += amount;
    }
}