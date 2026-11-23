package com.example.bankaccount.audit;

import com.example.bankaccount.domain.Transaction;
import java.util.List;
import java.util.Optional;

public interface AuditGroupSource {
  Optional<List<Transaction>> takeCompletedGroup() throws InterruptedException;

  void stopAccepting();

  String unfinishedWork();
}
