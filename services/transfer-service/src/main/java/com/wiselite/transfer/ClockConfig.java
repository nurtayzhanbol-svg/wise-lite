package com.wiselite.transfer;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /** Injected everywhere instead of calling Instant.now(), so tests can control time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
