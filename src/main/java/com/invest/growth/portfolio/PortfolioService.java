package com.invest.growth.portfolio;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Holding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);
    private static final Duration MAX_CACHE_AGE = Duration.ofSeconds(60);

    private record Snapshot(List<HoldingView> holdings, Instant fetchedAt) {
    }

    private volatile Snapshot snapshot;
    private final KiteProperties props;
    private final KiteSessionStore store;

    public PortfolioService(KiteProperties props, KiteSessionStore store) {
        this.props = props;
        this.store = store;
    }

    /** Returns the holdings cached by the background refresh, falling back to a live Kite call. */
    public List<HoldingView> getHoldings() {
        Snapshot s = snapshot;
        if (s != null && store.accessToken().isPresent()
                && Duration.between(s.fetchedAt(), Instant.now()).compareTo(MAX_CACHE_AGE) < 0) {
            return s.holdings();
        }
        return refresh();
    }

    public void clearCache() {
        snapshot = null;
    }

    public Instant lastRefreshed() {
        Snapshot s = snapshot;
        return s == null ? null : s.fetchedAt();
    }

    /** One Kite call returns LTP, day change and P&L inputs for every holding. */
    public List<HoldingView> refresh() {
        List<HoldingView> fresh = fetchFromKite();
        accept(Instant.now(), fresh);
        return fresh;
    }

    /** Stores a snapshot, whether it came from a direct call or from a broker message. */
    public void accept(Instant fetchedAt, List<HoldingView> holdings) {
        Snapshot current = snapshot;
        if (current == null || !fetchedAt.isBefore(current.fetchedAt())) {
            snapshot = new Snapshot(holdings, fetchedAt);
            log.debug("Cached {} holdings", holdings.size());
        }
    }

    /** Calls Kite without touching the cache. */
    public List<HoldingView> fetchFromKite() {
        String token = store.accessToken()
                .orElseThrow(() -> new KiteAuthException("Not logged in to Kite. Visit /kite/login."));
        try {
            // KiteConnect is stateful, so use a fresh instance per call
            KiteConnect kite = new KiteConnect(props.apiKey());
            kite.setAccessToken(token);
            store.userId().ifPresent(kite::setUserId);
            kite.setSessionExpiryHook(store::clear);
            return merge(kite.getHoldings());
        } catch (Throwable t) { // KiteException extends Throwable
            throw new KiteAuthException("Failed to fetch Kite holdings: " + t.getMessage(), t);
        }
    }

    /** Unmapped Kite response, to inspect exactly how normal and MTF holdings are returned. */
    public List<Holding> rawHoldings() {
        String token = store.accessToken()
                .orElseThrow(() -> new KiteAuthException("Not logged in to Kite. Visit /kite/login."));
        try {
            KiteConnect kite = new KiteConnect(props.apiKey());
            kite.setAccessToken(token);
            store.userId().ifPresent(kite::setUserId);
            return kite.getHoldings();
        } catch (Throwable t) {
            throw new KiteAuthException("Failed to fetch Kite holdings: " + t.getMessage(), t);
        }
    }

    private record Lot(String symbol, String exchange, int quantity, double avg, double ltp,
                       double dayPct, double dayPerShare, int mtfQuantity, double mtfCost, double mtfOwn) {
    }

    /**
     * One row per stock with normal and MTF quantity combined; an entry with product MTF is also handled.
     */
    static List<HoldingView> merge(List<Holding> holdings) {
        Map<String, Lot> bySymbol = new LinkedHashMap<>();
        for (Holding h : holdings) {
            int qty = effectiveQuantity(h);
            if (h.tradingSymbol == null || qty <= 0) {
                continue;
            }
            int mtfQty = "MTF".equalsIgnoreCase(h.product) ? qty : mtfQuantity(h);
            Lot lot = new Lot(h.tradingSymbol, h.exchange, qty, effectiveAveragePrice(h), nz(h.lastPrice),
                    h.dayChangePercentage, h.dayChange, mtfQty, mtfCost(h, qty, mtfQty), mtfOwn(h, qty, mtfQty));
            bySymbol.merge(h.tradingSymbol, lot, (x, y) -> {
                int total = x.quantity() + y.quantity();
                double avg = (x.avg() * x.quantity() + y.avg() * y.quantity()) / total;
                return new Lot(x.symbol(), x.exchange(), total, avg, x.ltp(), x.dayPct(), x.dayPerShare(),
                        x.mtfQuantity() + y.mtfQuantity(), x.mtfCost() + y.mtfCost(), x.mtfOwn() + y.mtfOwn());
            });
        }
        return bySymbol.values().stream()
                .map(l -> HoldingView.of(l.symbol(), l.exchange(), l.quantity(), l.avg(), l.ltp(),
                        l.dayPct(), l.dayPerShare()).withMtf(l.mtfQuantity(), l.mtfCost(), l.mtfOwn()))
                .sorted(Comparator.comparing(HoldingView::tradingSymbol))
                .toList();
    }

    public List<String> getTickers() {
        return getHoldings().stream().map(HoldingView::tradingSymbol).toList();
    }

    /**
     * Kite reports MTF separately: {@code quantity} is the normal (CNC) quantity and {@code mtf.quantity} the MTF
     * quantity. The top-level pnl and averagePrice already cover both, so the total is the sum.
     */
    private static int mtfQuantity(Holding h) {
        return h.mtf != null ? Math.max(h.mtf.quantity, 0) : 0;
    }

    private static double mtfCost(Holding h, int qty, int mtfQty) {
        if (mtfQty <= 0) {
            return 0;
        }
        double avg = h.mtf != null && nz(h.mtf.averagePrice) > 0 ? nz(h.mtf.averagePrice) : nz(h.averagePrice);
        return avg * mtfQty;
    }

    /** Kite's MTF initial margin is the part paid from the client's own funds. */
    private static double mtfOwn(Holding h, int qty, int mtfQty) {
        return h.mtf != null ? nz(h.mtf.initialMargin) : 0;
    }

    private static int effectiveQuantity(Holding h) {
        return Math.max(h.quantity, 0) + mtfQuantity(h);
    }

    private static double effectiveAveragePrice(Holding h) {
        double avg = nz(h.averagePrice);
        if (avg > 0 || h.mtf == null) {
            return avg;
        }
        return nz(h.mtf.averagePrice);
    }

    private static double nz(Double d) {
        return d == null ? 0d : d;
    }
}
