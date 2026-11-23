package com.example.bankaccount.integration;

import com.example.bankaccount.tracker.InMemoryBalanceTracker;
import static org.junit.jupiter.api.Assertions.*;

import com.example.bankaccount.BankAccountApplication;
import com.example.bankaccount.domain.Transaction;
import com.example.bankaccount.producer.TransactionProducer;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

@org.junit.jupiter.api.Tag("integration")
class BalanceApplicationTest {

  /** Starts the real HTTP server and checks the exact negative balance, OpenAPI schema and Swagger UI endpoints. */
  @Test
  void realApplicationServesBalanceOpenApiAndSwagger() throws Exception {
    try (
      var context = SpringApplication.run(
        BankAccountApplication.class,
        "--server.port=0"
      )
    ) {
      var producer = context.getBean(TransactionProducer.class);
      assertFalse(
        context
          .getBeansOfType(com.example.bankaccount.audit.AuditWorker.class)
          .isEmpty()
      );
      assertTrue(producer.isRunning());
      producer.close();
      var tracker = context.getBean(InMemoryBalanceTracker.class);
      BigDecimal existing = tracker.exactBalance();
      if (existing.signum() != 0) {
        tracker.processTransaction(
          new Transaction(UUID.randomUUID(), existing.negate())
        );
      }
      tracker.processTransaction(
        new Transaction(UUID.randomUUID(), new BigDecimal("-125.40"))
      );
      int port = ((WebServerApplicationContext) context)
        .getWebServer()
        .getPort();
      try (
        var client = HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build()
      ) {
        var balance = get(client, port, "/balance");
        assertEquals(200, balance.statusCode());
        assertTrue(balance.body().contains("\"GBP\""));
        assertTrue(balance.body().contains("\"-125.40\""));
        var api = get(client, port, "/v3/api-docs");
        assertEquals(200, api.statusCode());
        assertTrue(api.body().contains("\"/balance\""));
        assertTrue(api.body().contains("Signed balance in pounds"));
        var swagger = get(client, port, "/swagger-ui/index.html");
        assertEquals(200, swagger.statusCode());
        assertTrue(swagger.body().contains("Swagger UI"));


      }
    }
  }

  private HttpResponse<String> get(
    HttpClient client,
    int port,
    String path
  ) throws Exception {
    return client.send(
      HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }
}
