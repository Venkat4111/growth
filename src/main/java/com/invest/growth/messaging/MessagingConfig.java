package com.invest.growth.messaging;

import java.util.Map;
import java.util.UUID;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topology: the scheduler drops refresh requests on a queue that keeps only the newest one, a single worker
 * calls Kite, and results are broadcast on a fanout exchange so every app instance updates its own cache.
 */
@Configuration
public class MessagingConfig {

    /** Names are prefixed so a second app or a test can use the same broker without sharing messages. */
    public static final String REFRESH_QUEUE_EXPR = "${portfolio.messaging.prefix:portfolio}.refresh.requests";

    private final String prefix;

    public MessagingConfig(@Value("${portfolio.messaging.prefix:portfolio}") String prefix) {
        this.prefix = prefix;
    }

    public String refreshQueueName() {
        return prefix + ".refresh.requests";
    }

    public String updatesExchangeName() {
        return prefix + ".updates";
    }

    @Bean
    Queue refreshQueue() {
        // Length 1 + drop-head: if Kite is slow, stale requests are discarded instead of piling up
        return QueueBuilder.durable(refreshQueueName())
                .withArguments(Map.of("x-max-length", 1, "x-overflow", "drop-head"))
                .build();
    }

    @Bean
    FanoutExchange updatesExchange() {
        return new FanoutExchange(updatesExchangeName(), false, false);
    }

    @Bean
    Queue portfolioUpdatesQueue() {
        // RabbitMQ 4 rejects transient non-exclusive queues, so use a durable auto-delete queue per instance
        return new Queue(updatesExchangeName() + "." + UUID.randomUUID(), true, false, true);
    }

    @Bean
    Binding updatesBinding(Queue portfolioUpdatesQueue, FanoutExchange updatesExchange) {
        return BindingBuilder.bind(portfolioUpdatesQueue).to(updatesExchange);
    }

    @Bean
    MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
