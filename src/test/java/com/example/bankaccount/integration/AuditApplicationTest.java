package com.example.bankaccount.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.example.bankaccount.BankAccountApplication;
import com.example.bankaccount.domain.Transaction;
import com.example.bankaccount.producer.TransactionProducer;
import com.example.bankaccount.tracker.InMemoryBalanceTracker;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(OutputCaptureExtension.class)
@org.junit.jupiter.api.Tag("integration")
class AuditApplicationTest {

  @Test
  void defaultApplicationPrintsOneCompleteSubmissionAndReportsRemainder(
    CapturedOutput output
  ) {
    ObjectMapper mapper;
    BigDecimal expectedTotal;
    try (
      var context = SpringApplication.run(
        BankAccountApplication.class,
        "--server.port=0"
      )
    ) {
      var producer = context.getBean(TransactionProducer.class);
      producer.close();
      long alreadyAccepted =
        producer.creditsProduced() + producer.debitsProduced();
      var tracker = context.getBean(InMemoryBalanceTracker.class);
      // Finish the producer's partial group, then supply an independently known group.
      var tx = new Transaction(UUID.randomUUID(), new BigDecimal("-2000.01"));
      for (long i = alreadyAccepted; i < 2001; i++) tracker.processTransaction(
        tx
      );
      expectedTotal = tx.amount().abs().multiply(BigDecimal.valueOf(1000));
      mapper = context.getBean(ObjectMapper.class);
    }
    // Each printed envelope starts and ends with a brace at column zero.
    var documents = java.util.regex.Pattern.compile("(?ms)^\\{\\R.*?^\\}$")
      .matcher(output.getOut()).results().map(match -> match.group()).toList();
    assertEquals(2, documents.size());
    for (var document : documents) {
      var payload = mapper.readTree(document).get("submission");
      assertEquals(payload.get("batchCount").asInt(), payload.get("batches").size());
      assertTrue(document.lines().anyMatch(line -> line.equals("      {")));
    }
    assertTrue(
      output
        .getAll()
        .contains("Published audit group: transactions=1000, batches=3")
    );
    var submission = mapper.readTree(documents.getLast()).get("submission");
    assertEquals(3, submission.get("batchCount").asInt());
    assertTrue(submission.get("minimumProven").asBoolean());
    assertFalse(submission.get("searchTimedOut").asBoolean());
    int count = 0;
    BigDecimal total = BigDecimal.ZERO;
    for (var batch : submission.get("batches")) {
      count += batch.get("countOfTransactions").asInt();
      total = total.add(
        new BigDecimal(batch.get("totalValueOfAllTransactions").asText())
      );
    }
    assertEquals(1000, count);
    assertEquals(0, expectedTotal.compareTo(total));
    assertTrue(output.getOut().contains("1 pending transactions"));
  }
}
