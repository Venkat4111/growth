package com.invest.growth.portfolio;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.User;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class KiteAuthService {

    private final KiteProperties props;
    private final KiteSessionStore store;

    private static final Logger log = LoggerFactory.getLogger(KiteAuthService.class);

    private final PortfolioService portfolio;

    public KiteAuthService(KiteProperties props, KiteSessionStore store, PortfolioService portfolio) {
        this.props = props;
        this.store = store;
        this.portfolio = portfolio;
    }

    public String loginUrl() {
        return new KiteConnect(props.apiKey()).getLoginURL();
    }

    public void completeLogin(String requestToken) {
        try {
            KiteConnect kite = new KiteConnect(props.apiKey());
            User user = kite.generateSession(requestToken, props.apiSecret());
            store.save(user.accessToken, user.userId);
        } catch (Throwable t) { // KiteException extends Throwable
            throw new KiteAuthException("Kite session generation failed: " + t.getMessage(), t);
        }
    }

    /** Invalidates the Kite session (best effort) and forgets the token and cached holdings. */
    public void logout() {
        store.accessToken().ifPresent(token -> {
            try {
                KiteConnect kite = new KiteConnect(props.apiKey());
                kite.setAccessToken(token);
                kite.logout();
            } catch (Throwable t) { // KiteException extends Throwable; the local logout must still happen
                log.warn("Kite logout call failed, clearing local session anyway: {}", t.getMessage());
            }
        });
        store.clear();
        portfolio.clearCache();
    }

    public boolean isAuthenticated() {
        return store.accessToken().isPresent();
    }
}
