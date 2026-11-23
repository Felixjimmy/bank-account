package com.example.bankaccount.audit;

import com.example.bankaccount.domain.AuditResult;
import com.example.bankaccount.domain.Transaction;
import java.util.List;

public interface AuditPackingStrategy {
  AuditResult pack(List<Transaction> transactions);
}
