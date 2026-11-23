package com.example.bankaccount.audit;

import com.example.bankaccount.tracker.InMemoryBalanceTracker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class AuditConfiguration {

  /** Creates the audit worker and publishes completed submissions as JSON. */
  @Bean(initMethod = "start", destroyMethod = "close")
  AuditWorker auditWorker(InMemoryBalanceTracker tracker, ObjectMapper mapper,
      @Value("${bank-account.audit.shutdown-timeout:10s}") Duration shutdownTimeout,
      @Value("${bank-account.audit.exact-search-timeout:10s}") Duration exactSearchTimeout) {
    var publisher = new ConsoleAuditPublisher(mapper);
    return new AuditWorker(tracker, publisher::publish, shutdownTimeout, exactSearchTimeout);
  }
}
