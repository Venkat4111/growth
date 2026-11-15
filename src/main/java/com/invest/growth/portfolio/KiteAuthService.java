package com.invest.growth.portfolio;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.User;

import org.springframework.stereotype.Service;

@Service
public class KiteAuthService {

    private final KiteProperties props;
    private final KiteSessionStore store;

    public KiteAuthService(KiteProperties props, KiteSessionStore store) {
        this.props = props;
        this.store = store;
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

    public boolean isAuthenticated() {
        return store.accessToken().isPresent();
    }
}
