package com.example.bankaccount.audit;

import com.example.bankaccount.domain.AuditBatchSummary;
import com.example.bankaccount.domain.AuditEnvelope;
import com.example.bankaccount.domain.AuditSubmission;
import com.example.bankaccount.tracker.InMemoryBalanceTracker;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class AuditConfiguration {

  /** Creates the audit worker and publishes completed submissions as JSON. */
  @Bean(initMethod = "start", destroyMethod = "close")
  AuditWorker auditWorker(InMemoryBalanceTracker tracker, ObjectMapper mapper,
      @Value("${bank-account.audit.shutdown-timeout:10s}") Duration shutdownTimeout,
      @Value("${bank-account.audit.exact-search-timeout:10s}") Duration exactSearchTimeout) {
    var separators = Separators.createDefaultInstance()
      .withObjectNameValueSpacing(Separators.Spacing.AFTER);
    var writer = mapper.writer().with(new DefaultPrettyPrinter(separators)
      .withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE));
    return new AuditWorker(tracker, result -> {
      var batches = result
        .batches()
        .stream()
        .map(batch ->
          new AuditBatchSummary(
            batch.totalValueOfAllTransactions(),
            batch.transactions().size()
          )
        )
        .toList();
      var submission = new AuditSubmission(
        UUID.randomUUID(),
        batches.size(),
        result.volumeLowerBound(),
        result.minimumProven(),
        result.searchTimedOut(),
        batches
      );
      var out = System.out;
      out.println(writer.writeValueAsString(new AuditEnvelope(submission)));

      if (out.checkError()) {
        throw new IllegalStateException(
          "Could not write audit submission to console"
        );
      }
    }, shutdownTimeout, exactSearchTimeout);
  }
}
