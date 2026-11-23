package com.example.bankaccount.integration;

import com.example.bankaccount.producer.TransactionProducer;
import com.example.bankaccount.producer.ProducerConfiguration;
import static org.junit.jupiter.api.Assertions.*;

import com.example.bankaccount.domain.Transaction;
import com.example.bankaccount.service.BankAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@org.junit.jupiter.api.Tag("integration")
class ProducerConfigurationTest {

  private final ApplicationContextRunner contextRunner =
    new ApplicationContextRunner().withUserConfiguration(
      ProducerConfiguration.class
    );

  /** Checks the application context omits the producer when no account service is available. */
  @Test
  void doesNotRegisterProducerWithoutBankAccountService() {
    contextRunner.run(context ->
      assertFalse(context.containsBean("transactionProducer"))
    );
  }

  /** Checks the application context creates and starts the producer when an account service is present. */
  @Test
  void registersAndStartsProducerWhenBankAccountServiceIsAvailable() {
    contextRunner
      .withBean(BankAccountService.class, () ->
        new BankAccountService() {
          public void processTransaction(Transaction transaction) {}

          public double retrieveBalance() {
            return 0;
          }
        }
      )
      .run(context -> {
        assertTrue(context.containsBean("transactionProducer"));
        assertTrue(context.getBean(TransactionProducer.class).isRunning());
      });
  }
}
