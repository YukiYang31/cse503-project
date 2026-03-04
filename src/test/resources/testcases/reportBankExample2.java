package testcases;

public class reportBankExample2 {
    static class BankAccount {
        Object owner;
        int amount;

        BankAccount(Object owner, int amount) {  // callee: constructor
            this.owner = owner;
            this.amount = amount;
        }
    }

    class Wallet {
        BankAccount account;

        void swapAccount(Object newOwner) {
            BankAccount temp = new BankAccount(newOwner, 0);  // interprocedural
            this.account = temp;
        }
    }
    
}
