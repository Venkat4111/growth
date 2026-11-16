package com.invest.growth.messaging;

import com.invest.growth.portfolio.PortfolioService;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Receives broadcast snapshots and refreshes this instance's in-memory cache. */
@Component
public class HoldingsUpdateListener {

    private final PortfolioService portfolio;

    public HoldingsUpdateListener(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    @RabbitListener(queues = "#{portfolioUpdatesQueue.name}")
    public void onUpdate(HoldingsUpdate update) {
        portfolio.accept(update.fetchedAt(), update.holdings());
    }
}
