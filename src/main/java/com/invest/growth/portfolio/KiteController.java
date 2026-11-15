package com.invest.growth.portfolio;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/kite")
public class KiteController {

    private static final Logger log = LoggerFactory.getLogger(KiteController.class);

    private final KiteAuthService auth;
    private final PortfolioService portfolio;

    public KiteController(KiteAuthService auth, PortfolioService portfolio) {
        this.auth = auth;
        this.portfolio = portfolio;
    }

    @GetMapping("/login")
    public ResponseEntity<Void> login() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", auth.loginUrl())
                .build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam("request_token") String requestToken,
                                         @RequestParam(value = "status", defaultValue = "success") String status) {
        if (!"success".equalsIgnoreCase(status)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        auth.completeLogin(requestToken);
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", "/").build();
    }

    @GetMapping("/holdings")
    public List<HoldingView> holdings() {
        return portfolio.getHoldings();
    }

    @ExceptionHandler(KiteAuthException.class)
    public ResponseEntity<String> onAuthError(KiteAuthException e) {
        log.warn("Kite error: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(e.getMessage());
    }
}
