package com.invest.growth.advisor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.invest.growth.portfolio.HoldingView;
import com.invest.growth.rag.RetrievedChunk;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class AdvisoryHistoryRepository {

    private static final String COLUMNS = """
            id, generated_at, ticker, risk_zone, action, time_limit, management_summary,
            rationale_points::text AS rationale_points, evidence::text AS evidence, evidence_hash,
            quantity, avg_price, last_price, total_change_percent, model""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AdvisoryHistoryRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void save(HoldingView h, StockAdvisoryResponse a, List<RetrievedChunk> evidence,
                     String evidenceHash, String model) {
        jdbc.sql("""
                INSERT INTO advisory_history
                  (ticker, risk_zone, action, time_limit, management_summary, rationale_points, evidence,
                   evidence_hash, quantity, avg_price, last_price, total_change_percent, model)
                VALUES (:ticker, :risk, :action, :timeLimit, :summary, CAST(:rationale AS jsonb),
                        CAST(:evidence AS jsonb), :hash, :qty, :avg, :ltp, :pct, :model)""")
                .param("ticker", h.tradingSymbol())
                .param("risk", a.riskZone().name())
                .param("action", a.action().name())
                .param("timeLimit", a.timeLimit())
                .param("summary", a.managementSummary())
                .param("rationale", json.writeValueAsString(a.rationalePoints()))
                .param("evidence", json.writeValueAsString(evidence))
                .param("hash", evidenceHash)
                .param("qty", h.quantity())
                .param("avg", h.averagePrice())
                .param("ltp", h.lastPrice())
                .param("pct", h.totalChangePercent())
                .param("model", model)
                .update();
    }

    public Optional<StoredAdvisory> latest(String ticker) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM advisory_history WHERE ticker = :t "
                        + "ORDER BY generated_at DESC, id DESC LIMIT 1")
                .param("t", ticker)
                .query(this::map)
                .optional();
    }

    public List<StoredAdvisory> history(String ticker, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM advisory_history WHERE ticker = :t "
                        + "ORDER BY generated_at DESC, id DESC LIMIT :n")
                .param("t", ticker)
                .param("n", limit)
                .query(this::map)
                .list();
    }

    private StoredAdvisory map(ResultSet rs, int row) throws SQLException {
        List<String> rationale = json.readValue(rs.getString("rationale_points"), new TypeReference<>() {
        });
        List<RetrievedChunk> evidence = json.readValue(rs.getString("evidence"), new TypeReference<>() {
        });
        var advisory = new StockAdvisoryResponse(rs.getString("ticker"),
                RiskZone.valueOf(rs.getString("risk_zone")), TradeAction.valueOf(rs.getString("action")),
                rs.getString("time_limit"), rs.getString("management_summary"), rationale);
        return new StoredAdvisory(rs.getLong("id"),
                rs.getObject("generated_at", OffsetDateTime.class).toInstant(),
                advisory, rs.getString("evidence_hash"), evidence,
                (Integer) rs.getObject("quantity"), rs.getBigDecimal("avg_price"),
                rs.getBigDecimal("last_price"), rs.getBigDecimal("total_change_percent"),
                rs.getString("model"));
    }
}
