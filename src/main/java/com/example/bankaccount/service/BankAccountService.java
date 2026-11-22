package com.example.bankaccount.service;

import com.example.bankaccount.domain.Transaction;

public interface BankAccountService {
  /** Processes and accepts a transaction according to the implementation's account behavior. */
  void processTransaction(Transaction transaction);

  /** Returns the current account balance as a double for the required service contract. */
  double retrieveBalance();
}
