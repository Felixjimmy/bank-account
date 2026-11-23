package com.example.bankaccount.integration;

import com.example.bankaccount.audit.AuditWorker;
import static org.junit.jupiter.api.Assertions.*;

import com.example.bankaccount.BankAccountApplication;
import com.example.bankaccount.domain.Transaction;
import com.example.bankaccount.producer.TransactionProducer;
import com.example.bankaccount.tracker.InMemoryBalanceTracker;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Opt-in loopback HTTP measurement with a preloaded, continuously draining queue. */
class BalanceLatencyBenchmark {

  private static final int GROUPS = 1500;

  @Test
  void measureDefaultApplication() throws Exception {
    var console = System.out;
    var publications = new AtomicInteger();
    // Forward the real publisher's JSON and count completed writes.
    System.setOut(
      new PrintStream(console, true) {
        @Override
        public void println(String line) {
          super.println(line);
          if (
            line != null && line.stripLeading().startsWith("{") && line.contains("\"submission\":")
          ) publications.incrementAndGet();
        }
      }
    );
    try (
      var context = SpringApplication.run(
        BankAccountApplication.class,
        "--server.port=0"
      );
      var client = HttpClient.newHttpClient();
      var executor = Executors.newSingleThreadExecutor()
    ) {
      assertTrue(context.getBean(TransactionProducer.class).isRunning());
      context.getBean(AuditWorker.class);
      var tracker = context.getBean(InMemoryBalanceTracker.class);
      var mapper = context.getBean(ObjectMapper.class);
      int port = ((WebServerApplicationContext) context)
        .getWebServer()
        .getPort();
      var request = HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + port + "/balance")
      )
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();
      for (int i = 0; i < 40; i++) client.send(
        request,
        HttpResponse.BodyHandlers.discarding()
      );
      var feed = new AtomicBoolean(true);
      var load = executor.submit(() -> {
        long id = 0;
        while (feed.get()) {
          var amount = BigDecimal.valueOf((id & 1) == 0 ? 200 : -200);
          tracker.processTransaction(
            new Transaction(new UUID(0, ++id), amount)
          );
        }
      });
      var requests = new ArrayList<Long>();
      var overlapping = new ArrayList<Long>();
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (publications.get() == 0 && System.nanoTime() < deadline)
          Thread.sleep(1);
        assertTrue(publications.get() > 0);
        for (int i = 0; i < 200; i++) {
          long start = System.nanoTime();
          int before = publications.get();
          var response = client.send(
            request,
            HttpResponse.BodyHandlers.ofString()
          );
          int after = publications.get();
          long elapsed = System.nanoTime() - start;
          assertEquals(200, response.statusCode());
          var body = mapper.readTree(response.body());
          assertEquals("GBP", body.get("currency").asText());
          assertTrue(body.get("balance").asText().matches("-?\\d+\\.\\d{2}"));
          requests.add(elapsed);
          if (after > before) overlapping.add(elapsed);
        }
      } finally {
        feed.set(false);
        load.get(10, TimeUnit.SECONDS);
      }
      assertFalse(overlapping.isEmpty());
      report("default-load", requests);
      report("default-confirmed-publication", overlapping);
    } finally {
      System.setOut(console);
    }
  }

  @Test
  void measure() throws Exception {
    try (
      var context = SpringApplication.run(
        BalanceOnlyMeasurement.class,
        "--server.port=0",
        "--bank-account.tracker.max-queued-audit-groups=" + GROUPS
      );
      var client = HttpClient.newHttpClient()
    ) {
      var tracker = context.getBean(InMemoryBalanceTracker.class);
      int port = ((WebServerApplicationContext) context)
        .getWebServer()
        .getPort();
      var request = HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + port + "/balance")
      )
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();
      var random = new Random(317);
      var fixture = new ArrayList<Transaction>();
      for (int i = 0; i < InMemoryBalanceTracker.GROUP_SIZE; i++) {
        int pennies = random.nextInt(20000, 50000001);
        fixture.add(
          new Transaction(
            new UUID(1, i),
            BigDecimal.valueOf(i % 2 == 0 ? pennies : -pennies, 2)
          )
        );
      }
      for (int i = 0; i < GROUPS; i++) fixture.forEach(
        tracker::processTransaction
      );

      String balance = tracker.exactBalance().toPlainString();
      for (int i = 0; i < 40; i++) timeRead(client, request, balance);
      var idle = new ArrayList<Long>();
      for (int i = 0; i < 200; i++) idle.add(
        timeRead(client, request, balance)
      );
      report("idle", idle);

      var firstPublished = new CountDownLatch(1);
      var published = new AtomicInteger();
      var lastPublication = new AtomicLong(Long.MAX_VALUE);
      var busy = new ArrayList<Long>();
      try (
        var worker = new AuditWorker(tracker, result -> {
          if (published.incrementAndGet() == GROUPS) lastPublication.set(
            System.nanoTime()
          );
          firstPublished.countDown();
        })
      ) {
        worker.start();
        assertTrue(firstPublished.await(10, TimeUnit.SECONDS));
        while (busy.size() < 200 && published.get() < GROUPS) {
          long latency = timeRead(client, request, balance);
          if (System.nanoTime() < lastPublication.get()) busy.add(latency);
        }
      }
      assertEquals(GROUPS, published.get());
      assertFalse(busy.isEmpty());
      report("busy", busy);
    }
  }

  // Test-only wiring lets this measurement start its worker after the idle baseline.
  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  @org.springframework.boot.autoconfigure.EnableAutoConfiguration
  @org.springframework.context.annotation.Import({
    InMemoryBalanceTracker.class,
    com.example.bankaccount.tracker.BalanceController.class
  })
  static class BalanceOnlyMeasurement {}

  private long timeRead(
    HttpClient client,
    HttpRequest request,
    String balance
  ) throws Exception {
    long start = System.nanoTime();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    long elapsed = System.nanoTime() - start;
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("\"balance\":\"" + balance + "\""));
    return elapsed;
  }

  private void report(String phase, List<Long> samples) {
    Collections.sort(samples);
    System.out.printf(
      Locale.ROOT,
      "BALANCE_LATENCY %s n=%d p50=%.3f p95=%.3f p99=%.3f max=%.3f ms%n",
      phase,
      samples.size(),
      percentile(samples, 50),
      percentile(samples, 95),
      percentile(samples, 99),
      samples.getLast() / 1e6
    );
  }

  private double percentile(List<Long> sorted, int percent) {
    return sorted.get(Math.ceilDiv(percent * sorted.size(), 100) - 1) / 1e6;
  }
}
