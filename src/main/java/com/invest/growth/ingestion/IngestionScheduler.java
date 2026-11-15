package com.invest.growth.ingestion;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import com.invest.growth.portfolio.KiteAuthException;
import com.invest.growth.portfolio.PortfolioService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final PortfolioService portfolio;
    private final ScreenerScraper screener;
    private final EconomicTimesParser economicTimes;
    private final DocumentIndexer indexer;
    private final IngestionProperties props;
    private final IngestionControl control;

    public IngestionScheduler(PortfolioService portfolio, ScreenerScraper screener,
                              EconomicTimesParser economicTimes, DocumentIndexer indexer,
                              IngestionProperties props, IngestionControl control) {
        this.control = control;
        this.portfolio = portfolio;
        this.screener = screener;
        this.economicTimes = economicTimes;
        this.indexer = indexer;
        this.props = props;
    }

    @Scheduled(fixedRateString = "${ingestion.fixed-rate-ms:300000}")
    public void scheduledRun() {
        control.tick();
        if (!control.isEnabled()) {
            return;
        }
        run();
    }

    /** Runs one cycle unless one is already in progress; used by the schedule and the manual trigger. */
    public void run() {
        if (!control.tryStart()) {
            return;
        }
        int added = 0;
        String outcome = "OK";
        try {
            List<String> tickers = portfolio.getTickers();
            if (tickers.isEmpty()) {
                outcome = "No holdings";
                return;
            }
            AtomicInteger counter = new AtomicInteger();
            control.begin(tickers.size());
            Semaphore limit = new Semaphore(props.maxConcurrency());
            try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
                for (String ticker : tickers) {
                    exec.submit(() -> {
                        try {
                            limit.acquire();
                            try {
                                screener.fetch(ticker).forEach(i -> counter.addAndGet(safeIndex(i)));
                            } finally {
                                limit.release();
                            }
                        } catch (Exception e) {
                            log.warn("Screener ingestion failed for {}: {}", ticker, e.toString());
                        } finally {
                            control.step();
                        }
                    });
                }
            }
            // ET matching uses company names learned from Screener, so it runs afterwards
            economicTimes.fetch(tickers).forEach(i -> counter.addAndGet(safeIndex(i)));
            log.info("Ingestion cycle finished: {} new item(s) across {} ticker(s)", counter.get(), tickers.size());
            added = counter.get();
        } catch (KiteAuthException e) {
            log.info("Skipping ingestion: {}", e.getMessage());
            outcome = "Kite login required";
        } catch (Exception e) {
            log.error("Ingestion cycle failed", e);
            outcome = "Failed: " + e.getMessage();
        } finally {
            control.finish(added, outcome);
        }
    }

    private int safeIndex(IngestItem item) {
        try {
            return indexer.index(item) ? 1 : 0;
        } catch (Exception e) {
            log.warn("Indexing failed for {} ({}): {}", item.ticker(), item.docType(), e.toString());
            return 0;
        }
    }
}
