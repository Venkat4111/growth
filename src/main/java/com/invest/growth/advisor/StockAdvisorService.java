package com.invest.growth.advisor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import com.invest.growth.ingestion.HashUtil;
import com.invest.growth.portfolio.HoldingView;
import com.invest.growth.portfolio.PortfolioService;
import com.invest.growth.rag.RagRetriever;
import com.invest.growth.rag.RetrievedChunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StockAdvisorService {

    private static final Logger log = LoggerFactory.getLogger(StockAdvisorService.class);

    private static final String SYSTEM_PROMPT = """
            You are an equity research analyst covering Indian listed companies (NSE/BSE).
            Use ONLY the provided context (Screener.in fundamentals, concall/management commentary,
            corporate announcements and Economic Times news) plus the user's position data.
            Never invent facts, figures or dates that are not in the context.
            - riskZone must be assessed strictly from management commentary, debt/margin guidance
              and negative news catalysts: LOW, MODERATE, HIGH or CRITICAL.
            - action is BUY, HOLD or SELL for the user's existing position.
            - timeLimit is a specific horizon such as "1-3 Months", "Until Next Quarterly Results"
              or "Immediate / 1 Week".
            - managementSummary is a concise summary of the latest management guidelines. If the
              context has no management commentary, say so explicitly.
            - rationalePoints has 2 to 3 short points, each citing a specific fact from the context.
            This is a research summary, not investment advice.
            """;

    private static final String USER_PROMPT = """
            Ticker: {ticker}

            User position:
            {position}

            Retrieved context (most recent first):
            {context}

            {format}
            """;

    private final ChatClient chatClient;
    private final RagRetriever retriever;
    private final PortfolioService portfolio;
    private final AdvisorProperties props;
    private final AdvisoryHistoryRepository history;
    private final String model;
    private final BeanOutputConverter<StockAdvisoryResponse> converter =
            new BeanOutputConverter<>(StockAdvisoryResponse.class);
    private final Map<String, AdvisoryOutcome> cache = new ConcurrentHashMap<>();

    public StockAdvisorService(ChatClient.Builder builder, RagRetriever retriever,
                               PortfolioService portfolio, AdvisorProperties props,
                               AdvisoryHistoryRepository history,
                               @Value("${spring.ai.openai.chat.options.model:unknown}") String model) {
        this.history = history;
        this.model = model;
        this.chatClient = builder.defaultSystem(SYSTEM_PROMPT).build();
        this.retriever = retriever;
        this.portfolio = portfolio;
        this.props = props;
    }

    /** Advisories for the whole portfolio; entries newer than the cache TTL are reused. */
    public List<AdvisoryOutcome> adviseAll() {
        List<HoldingView> holdings = portfolio.getHoldings();
        Semaphore limit = new Semaphore(props.maxConcurrency());
        List<Future<AdvisoryOutcome>> futures = new ArrayList<>();
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (HoldingView h : holdings) {
                futures.add(exec.submit(() -> {
                    limit.acquire();
                    try {
                        return advise(h);
                    } finally {
                        limit.release();
                    }
                }));
            }
        }
        return futures.stream().map(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    public AdvisoryOutcome advise(HoldingView holding) {
        AdvisoryOutcome cached = cache.get(holding.tradingSymbol());
        if (cached != null && fresh(cached)) {
            return cached;
        }
        AdvisoryOutcome outcome = generate(holding);
        // keep the last good advisory if a refresh fails
        if (outcome instanceof AdvisoryOutcome.Failure && cached instanceof AdvisoryOutcome.Success) {
            return cached;
        }
        cache.put(holding.tradingSymbol(), outcome);
        return outcome;
    }

    private boolean fresh(AdvisoryOutcome o) {
        return o instanceof AdvisoryOutcome.Success
                && Duration.between(o.generatedAt(), Instant.now()).toMillis() < props.cacheTtlMs();
    }

    private AdvisoryOutcome generate(HoldingView h) {
        String ticker = h.tradingSymbol();
        Optional<StoredAdvisory> latest = latestStored(ticker);
        try {
            List<RetrievedChunk> evidence = retriever.retrieve(ticker);
            if (evidence.isEmpty()) {
                return latest.<AdvisoryOutcome>map(l -> fromStored(h, l, l.evidence()))
                        .orElseGet(() -> new AdvisoryOutcome.Failure(h,
                                "No indexed research data yet for " + ticker, Instant.now()));
            }
            String hash = evidenceHash(evidence);
            // Same evidence as the last analysis: reuse it instead of paying for another LLM call
            if (latest.isPresent() && latest.get().evidenceHash().equals(hash)) {
                return fromStored(h, latest.get(), evidence);
            }
            StockAdvisoryResponse raw = chatClient.prompt()
                    .user(u -> u.text(USER_PROMPT)
                            .param("ticker", ticker)
                            .param("position", position(h))
                            .param("context", context(evidence))
                            .param("format", converter.getFormat()))
                    .call()
                    .entity(converter);
            StockAdvisoryResponse advisory = normalize(raw, ticker);
            saveStored(h, advisory, evidence, hash);
            return new AdvisoryOutcome.Success(h, advisory, evidence, Instant.now());
        } catch (Exception e) {
            log.warn("Advisory generation failed for {}: {}", ticker, e.toString());
            return latest.<AdvisoryOutcome>map(l -> fromStored(h, l, l.evidence()))
                    .orElseGet(() -> new AdvisoryOutcome.Failure(h,
                            "Advisory unavailable: " + e.getMessage(), Instant.now()));
        }
    }

    private static AdvisoryOutcome fromStored(HoldingView h, StoredAdvisory s, List<RetrievedChunk> evidence) {
        return new AdvisoryOutcome.Success(h, s.advisory(), evidence, s.generatedAt());
    }

    // Persistence problems must never take the dashboard down
    private Optional<StoredAdvisory> latestStored(String ticker) {
        try {
            return history.latest(ticker);
        } catch (Exception e) {
            log.warn("Could not read advisory history for {}: {}", ticker, e.toString());
            return Optional.empty();
        }
    }

    private void saveStored(HoldingView h, StockAdvisoryResponse a, List<RetrievedChunk> evidence, String hash) {
        try {
            history.save(h, a, evidence, hash, model);
        } catch (Exception e) {
            log.warn("Could not save advisory for {}: {}", h.tradingSymbol(), e.toString());
        }
    }

    private static String evidenceHash(List<RetrievedChunk> evidence) {
        return HashUtil.sha256(evidence.stream()
                .map(c -> c.source() + "|" + c.docType() + "|" + c.publishedAt() + "|" + c.text())
                .collect(Collectors.joining("\n")));
    }

    private static StockAdvisoryResponse normalize(StockAdvisoryResponse r, String ticker) {
        if (r == null || r.riskZone() == null || r.action() == null) {
            throw new IllegalStateException("Incomplete model response");
        }
        List<String> points = r.rationalePoints() == null ? List.of()
                : r.rationalePoints().stream().limit(3).toList();
        return new StockAdvisoryResponse(ticker, r.riskZone(), r.action(),
                r.timeLimit(), r.managementSummary(), points);
    }

    private static String position(HoldingView h) {
        return "Quantity: %d, Average buy price: ₹%s, Last traded price: ₹%s, Unrealized P&L: ₹%s (%s%%), Day change: %s%%"
                .formatted(h.quantity(), h.averagePrice(), h.lastPrice(),
                        h.unrealizedPnl(), h.totalChangePercent(), h.dayChangePercent());
    }

    private static String context(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(c -> "[%s | %s | %s]\n%s".formatted(c.source(), c.docType(), c.publishedAt(), c.text()))
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
