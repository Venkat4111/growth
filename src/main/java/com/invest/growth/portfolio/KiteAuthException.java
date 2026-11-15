package com.invest.growth.portfolio;

public class KiteAuthException extends RuntimeException {
    public KiteAuthException(String message) {
        super(message);
    }

    public KiteAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
