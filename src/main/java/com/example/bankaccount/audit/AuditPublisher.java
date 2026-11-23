package com.example.bankaccount.audit;

import com.example.bankaccount.domain.AuditResult;

public interface AuditPublisher {
  void publish(AuditResult result);
}
