package com.wiselite.payout;

import org.springframework.boot.SpringApplication;
import java.time.Clock;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Consumes transfer events and pays out FUNDED transfers. M4: records payout requests
 * idempotently. M6: calls the rails simulator with retries and handles unknown outcomes.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(PayoutProperties.class)
public class PayoutWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayoutWorkerApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CircuitBreaker railsCircuitBreaker(PayoutProperties p, Clock clock) {
        return new CircuitBreaker(p.breakerFailureThreshold(), p.breakerOpenDuration(), clock);
    }
}
