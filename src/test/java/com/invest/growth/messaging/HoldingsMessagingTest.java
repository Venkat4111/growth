package com.invest.growth.messaging;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.List;

import com.invest.growth.portfolio.HoldingView;
import com.invest.growth.portfolio.KiteSessionStore;
import com.invest.growth.portfolio.PortfolioService;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "OPENAI_API_KEY=test", "KITE_API_KEY=test", "KITE_API_SECRET=test",
        "ingestion.fixed-rate-ms=3600000", "portfolio.refresh-rate-ms=3600000",
        "spring.sql.init.mode=never", "portfolio.messaging.prefix=portfolio-test"
})
class HoldingsMessagingTest {

    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    PortfolioService portfolio;
    @Autowired
    KiteSessionStore store;

    @Test
    void broadcastUpdateReachesCache() throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", 5672), 500);
        } catch (Exception e) {
            Assumptions.abort("RabbitMQ not reachable");
        }
        store.save("fake-token", "u");
        HoldingView h = HoldingView.of("TEST", "NSE", 1, 10, 12, 1, 0.1);
        rabbit.convertAndSend("portfolio-test.updates", "", new HoldingsUpdate(Instant.now(), List.of(h)));

        Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(portfolio.getHoldings()).extracting(HoldingView::lastPrice)
                        .containsExactly(new BigDecimal("12.00")));
        store.clear();
    }
}
