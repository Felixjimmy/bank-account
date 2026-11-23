package com.example.bankaccount.audit;

import com.example.bankaccount.domain.AuditBatchSummary;
import com.example.bankaccount.domain.AuditEnvelope;
import com.example.bankaccount.domain.AuditResult;
import com.example.bankaccount.domain.AuditSubmission;
import java.io.PrintStream;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;

public final class ConsoleAuditPublisher implements AuditPublisher {
  private final ObjectWriter writer;
  private final PrintStream output;

  public ConsoleAuditPublisher(ObjectMapper mapper) {
    this(mapper, System.out);
  }

  ConsoleAuditPublisher(ObjectMapper mapper, PrintStream output) {
    Objects.requireNonNull(mapper, "mapper");
    this.output = Objects.requireNonNull(output, "output");
    var separators = Separators.createDefaultInstance()
      .withObjectNameValueSpacing(Separators.Spacing.AFTER);
    this.writer = mapper.writer().with(new DefaultPrettyPrinter(separators)
      .withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE));
  }

  @Override
  public void publish(AuditResult result) {
    Objects.requireNonNull(result, "result");
    var batches = result
      .batches()
      .stream()
      .map(batch -> new AuditBatchSummary(
        batch.totalValueOfAllTransactions(),
        batch.transactions().size()
      ))
      .toList();
    var submission = new AuditSubmission(
      UUID.randomUUID(),
      batches.size(),
      result.volumeLowerBound(),
      result.minimumProven(),
      result.searchTimedOut(),
      batches
    );
    output.println(writer.writeValueAsString(new AuditEnvelope(submission)));
    if (output.checkError()) {
      throw new IllegalStateException("Could not write audit submission to console");
    }
  }
}
