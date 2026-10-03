package com.wiselite.payout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Consumes transfer events and pays out FUNDED transfers. M4: records payout requests
 * idempotently. M6: calls the rails simulator with retries and handles unknown outcomes.
 */
@SpringBootApplication
public class PayoutWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayoutWorkerApplication.class, args);
    }
}
