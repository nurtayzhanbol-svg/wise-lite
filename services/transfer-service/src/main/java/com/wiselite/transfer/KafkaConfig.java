package com.wiselite.transfer;

import com.wiselite.events.Topics;
import com.wiselite.outbox.OutboxConfiguration;
import com.wiselite.transfer.transfer.IllegalStateTransitionException;
import com.wiselite.transfer.transfer.TransferNotFoundException;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Import(OutboxConfiguration.class)
public class KafkaConfig {

    /** 3 partitions keyed by transfer id; replication 1 because local Kafka has one broker. */
    @Bean
    NewTopic transferEvents() {
        return TopicBuilder.name(Topics.TRANSFER_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic payoutEvents() {
        return TopicBuilder.name(Topics.PAYOUT_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic payoutEventsDlt() {
        return TopicBuilder.name(Topics.PAYOUT_EVENTS + ".DLT").partitions(3).replicas(1).build();
    }

    /**
     * An event that contradicts our state (e.g. SETTLED for a transfer already refunded) is not
     * transient: retrying won't help. Dead-letter it for a human; retry everything else briefly.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template) {
        var backOff = new ExponentialBackOff(100, 2.0);
        backOff.setMaxElapsedTime(2_000);
        var handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), backOff);
        handler.addNotRetryableExceptions(IllegalStateTransitionException.class, TransferNotFoundException.class,
                IllegalArgumentException.class);
        return handler;
    }
}
