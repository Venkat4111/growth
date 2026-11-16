package com.invest.growth.portfolio;

import java.time.Instant;

import com.invest.growth.messaging.MessagingConfig;
import com.invest.growth.messaging.RefreshRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ticks every 2s by default. It only enqueues a request; the Kite call happens in the worker. */
@Component
public class PortfolioRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(PortfolioRefreshScheduler.class);

    private final PortfolioService portfolio;
    private final KiteSessionStore store;
    private final RabbitTemplate rabbit;
    private final MessagingConfig names;
    private volatile boolean brokerDown;

    public PortfolioRefreshScheduler(PortfolioService portfolio, KiteSessionStore store, RabbitTemplate rabbit,
                                     MessagingConfig names) {
        this.names = names;
        this.portfolio = portfolio;
        this.store = store;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${portfolio.refresh-rate-ms:2000}")
    public void tick() {
        if (store.accessToken().isEmpty()) {
            return;
        }
        try {
            rabbit.convertAndSend(names.refreshQueueName(), new RefreshRequest(Instant.now()));
            if (brokerDown) {
                log.info("RabbitMQ is reachable again");
                brokerDown = false;
            }
        } catch (AmqpException e) {
            if (!brokerDown) {
                log.warn("RabbitMQ unavailable, refreshing Kite holdings directly: {}", e.getMessage());
            }
            brokerDown = true;
            try {
                portfolio.refresh();
            } catch (RuntimeException ex) {
                log.debug("Direct Kite refresh failed: {}", ex.getMessage());
            }
        }
    }
}
