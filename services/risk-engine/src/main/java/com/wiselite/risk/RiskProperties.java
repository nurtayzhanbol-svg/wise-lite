package com.wiselite.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("wiselite.risk")
public record RiskProperties(String bootstrapServers, String stateDir, String processingGuarantee, RiskRules rules) {}
