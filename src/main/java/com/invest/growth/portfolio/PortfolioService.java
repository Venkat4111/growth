package com.invest.growth.portfolio;

import java.util.Comparator;
import java.util.List;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Holding;

import org.springframework.stereotype.Service;

@Service
public class PortfolioService {

    private final KiteProperties props;
    private final KiteSessionStore store;

    public PortfolioService(KiteProperties props, KiteSessionStore store) {
        this.props = props;
        this.store = store;
    }

    public List<HoldingView> getHoldings() {
        String token = store.accessToken()
                .orElseThrow(() -> new KiteAuthException("Not logged in to Kite. Visit /kite/login."));
        try {
            // KiteConnect is stateful, so use a fresh instance per call
            KiteConnect kite = new KiteConnect(props.apiKey());
            kite.setAccessToken(token);
            store.userId().ifPresent(kite::setUserId);
            kite.setSessionExpiryHook(store::clear);
            List<Holding> holdings = kite.getHoldings();
            return holdings.stream()
                    .filter(h -> h.quantity > 0 && h.tradingSymbol != null)
                    .map(h -> HoldingView.of(h.tradingSymbol, h.exchange, h.quantity,
                            nz(h.averagePrice), nz(h.lastPrice), h.dayChangePercentage, h.dayChange))
                    .sorted(Comparator.comparing(HoldingView::tradingSymbol))
                    .toList();
        } catch (Throwable t) { // KiteException extends Throwable
            throw new KiteAuthException("Failed to fetch Kite holdings: " + t.getMessage(), t);
        }
    }

    public List<String> getTickers() {
        return getHoldings().stream().map(HoldingView::tradingSymbol).toList();
    }

    private static double nz(Double d) {
        return d == null ? 0d : d;
    }
}
