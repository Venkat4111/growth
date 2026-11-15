package com.invest.growth.portfolio;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kite")
public record KiteProperties(String apiKey, String apiSecret, String redirectUrl) {
}
