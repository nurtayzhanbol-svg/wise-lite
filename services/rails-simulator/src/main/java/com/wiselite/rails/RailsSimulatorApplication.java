package com.wiselite.rails;

import java.util.Random;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * A fake payment rail (think SEPA / Faster Payments) with injectable faults: latency, failures
 * before and *after* processing (the "unknown outcome"), rejections and duplicate webhooks.
 * In-memory on purpose: it stands in for a third party, its durability is not ours to design.
 */
@SpringBootApplication
public class RailsSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(RailsSimulatorApplication.class, args);
    }

    /** Seeded so fault injection is reproducible. */
    @Bean
    Random random(@Value("${wiselite.rails.seed:42}") long seed) {
        return new Random(seed);
    }
}
