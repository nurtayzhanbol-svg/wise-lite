package com.wiselite.outbox;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Transactional outbox, shared by every service that publishes events. Import it and add the
 * {@code outbox_events} table to the service's own migrations (each service owns its schema).
 */
@Configuration(proxyBeanMethods = false)
@Import({OutboxWriter.class, OutboxRelay.class, OutboxRelayScheduler.class})
public class OutboxConfiguration {}
