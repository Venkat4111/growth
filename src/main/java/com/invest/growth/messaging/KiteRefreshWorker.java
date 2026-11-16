package com.invest.growth.messaging;

import java.time.Instant;
import java.util.List;

import com.invest.growth.portfolio.HoldingView;
import com.invest.growth.portfolio.KiteSessionStore;
import com.invest.growth.portfolio.PortfolioService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** Single consumer, so Kite is never called concurrently regardless of how slow it responds. */
@Component
public class KiteRefreshWorker {

    private static final Logger log = LoggerFactory.getLogger(KiteRefreshWorker.class);

    private final PortfolioService portfolio;
    private final KiteSessionStore store;
    private final RabbitTemplate rabbit;
    private final MessagingConfig names;
    private volatile boolean lastFailed;

    public KiteRefreshWorker(PortfolioService portfolio, KiteSessionStore store, RabbitTemplate rabbit,
                            MessagingConfig names) {
        this.names = names;
        this.portfolio = portfolio;
        this.store = store;
        this.rabbit = rabbit;
    }

    @RabbitListener(queues = MessagingConfig.REFRESH_QUEUE_EXPR, concurrency = "1")
    public void onRefreshRequest(RefreshRequest request) {
        if (store.accessToken().isEmpty()) {
            return;
        }
        try {
            List<HoldingView> holdings = portfolio.fetchFromKite();
            rabbit.convertAndSend(names.updatesExchangeName(), "",
                    new HoldingsUpdate(Instant.now(), holdings));
            if (lastFailed) {
                log.info("Kite holdings refresh recovered");
                lastFailed = false;
            }
        } catch (RuntimeException e) {
            // Swallow so the message is not redelivered; the next tick asks again
            if (!lastFailed) {
                log.warn("Kite holdings refresh failed: {}", e.getMessage());
            }
            lastFailed = true;
        }
    }
}
