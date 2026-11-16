package com.invest.growth.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.invest.growth.portfolio.HoldingView;
import com.invest.growth.portfolio.KiteAuthException;
import com.invest.growth.portfolio.PortfolioService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lightweight JSON read of the cached Kite snapshot, polled by the dashboard to update prices in place. */
@RestController
public class LivePriceController {

    public record Stock(String symbol, BigDecimal ltp, BigDecimal dayPct, BigDecimal dayAmount,
                        BigDecimal totalPct, BigDecimal pnl) {
    }

    public record Summary(BigDecimal invested, BigDecimal current, BigDecimal pnl, BigDecimal pnlPct,
                          BigDecimal dayAmount, BigDecimal ownMoney, BigDecimal ownMoneyPct, BigDecimal mtfOwn,
                          BigDecimal mtfBorrowed, BigDecimal mtfPnl, BigDecimal mtfPnlPct, BigDecimal normalPnl) {
    }

    public record Live(long fetchedAtMillis, long serverNowMillis, Summary summary, List<Stock> stocks) {
    }

    private final PortfolioService portfolio;

    public LivePriceController(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    @GetMapping("/portfolio/live")
    public ResponseEntity<Live> live() {
        List<HoldingView> holdings;
        try {
            holdings = portfolio.getHoldings();
        } catch (KiteAuthException e) {
            return ResponseEntity.status(401).build();
        }
        List<Stock> stocks = holdings.stream()
                .map(h -> new Stock(h.tradingSymbol(), h.lastPrice(), h.dayChangePercent(), h.dayChangeAmount(),
                        h.totalChangePercent(), h.unrealizedPnl()))
                .toList();
        BigDecimal invested = holdings.stream().map(HoldingView::investedValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal current = holdings.stream().map(HoldingView::currentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal day = holdings.stream().map(HoldingView::dayChangeAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pnl = current.subtract(invested);
        BigDecimal pnlPct = invested.signum() == 0 ? BigDecimal.ZERO
                : pnl.multiply(BigDecimal.valueOf(100)).divide(invested, 2, java.math.RoundingMode.HALF_UP);
        BigDecimal own = sum(holdings, HoldingView::ownMoney);
        BigDecimal mtfOwn = sum(holdings, HoldingView::mtfOwnMoney);
        BigDecimal mtfPnl = sum(holdings, HoldingView::mtfPnl);
        BigDecimal ownPct = pct(pnl, own);
        Instant fetched = portfolio.lastRefreshed();
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(new Live(fetched == null ? 0 : fetched.toEpochMilli(), System.currentTimeMillis(),
                        new Summary(invested, current, pnl, pnlPct, day, own, ownPct, mtfOwn,
                                sum(holdings, HoldingView::mtfBorrowed), mtfPnl, pct(mtfPnl, mtfOwn), sum(holdings, HoldingView::normalPnl)), stocks));
    }

    private static BigDecimal sum(List<HoldingView> list, java.util.function.Function<HoldingView, BigDecimal> f) {
        return list.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal base) {
        return base.signum() == 0 ? BigDecimal.ZERO
                : part.multiply(BigDecimal.valueOf(100)).divide(base, 2, java.math.RoundingMode.HALF_UP);
    }
}
