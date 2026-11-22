package com.example.bankaccount.producer;

import com.example.bankaccount.service.BankAccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class ProducerConfiguration {

  /** Creates the producer when an account service is available. */
  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnBean(BankAccountService.class)
  TransactionProducer transactionProducer(BankAccountService account) {
    log.info("Bank account service found; creating the transaction producer");
    return new TransactionProducer(account, new TransactionGenerator());
  }
}
