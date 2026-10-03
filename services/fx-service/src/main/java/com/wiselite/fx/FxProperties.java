package com.wiselite.fx;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("wiselite.fx")
public record FxProperties(
        URI ecbUrl,
        Duration refreshInterval,
        Duration maxStaleness,
        Duration quoteTtl,
        BigDecimal feePercentage,
        BigDecimal feeMinimum) {}
