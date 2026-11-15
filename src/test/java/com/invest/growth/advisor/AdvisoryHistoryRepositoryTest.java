package com.invest.growth.advisor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.invest.growth.portfolio.HoldingView;
import com.invest.growth.rag.RetrievedChunk;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs against the local Postgres; skipped when it is not reachable. */
class AdvisoryHistoryRepositoryTest {

    AdvisoryHistoryRepository repo;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource(
                System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/growth"),
                System.getenv().getOrDefault("DB_USERNAME", "growth"),
                System.getenv().getOrDefault("DB_PASSWORD", "growth_local"));
        try (var c = ds.getConnection()) {
            Assumptions.assumeTrue(c.isValid(2));
        } catch (Exception e) {
            Assumptions.abort("Postgres not available: " + e.getMessage());
        }
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        repo = new AdvisoryHistoryRepository(JdbcClient.create(ds), JsonMapper.builder().build());
    }

    @Test
    void savesAndReadsHistoryNewestFirst() {
        String ticker = "TEST" + UUID.randomUUID().toString().substring(0, 8);
        var h = HoldingView.of(ticker, "NSE", 10, 100, 90, -1.0, -1.0);
        var ev = List.of(new RetrievedChunk(ticker, "SCREENER", "NEWS", Instant.parse("2026-11-01T00:00:00Z"), "text"));
        repo.save(h, new StockAdvisoryResponse(ticker, RiskZone.LOW, TradeAction.HOLD, "1-3 Months", "s1", List.of("a")),
                ev, "hash1", "m");
        repo.save(h, new StockAdvisoryResponse(ticker, RiskZone.HIGH, TradeAction.SELL, "1 Week", "s2", List.of("b", "c")),
                ev, "hash2", "m");

        var all = repo.history(ticker, 10);
        assertEquals(2, all.size());
        assertEquals(RiskZone.HIGH, all.get(0).advisory().riskZone());
        assertEquals(List.of("b", "c"), all.get(0).advisory().rationalePoints());
        assertEquals("hash2", repo.latest(ticker).orElseThrow().evidenceHash());
        assertEquals("text", all.get(0).evidence().get(0).text());
        assertTrue(all.get(0).avgPrice().compareTo(new java.math.BigDecimal("100")) == 0);
    }
}
