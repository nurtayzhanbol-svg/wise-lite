package com.wiselite.payout;

import com.wiselite.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfig {

    public static final String DLT = Topics.TRANSFER_EVENTS + ".DLT";

    /**
     * Transient failures (DB blip): retry in-process with backoff. After the retries run out, or
     * immediately for {@link MalformedEventException}, publish to the DLT and move on, so one
     * poison record cannot block its partition forever.
     */
    @Bean
    DefaultErrorHandler errorHandler(KafkaOperations<Object, Object> template) {
        var backOff = new ExponentialBackOff(100, 2.0);
        backOff.setMaxElapsedTime(2_000);
        var handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), backOff);
        handler.addNotRetryableExceptions(MalformedEventException.class);
        return handler;
    }

    // Declared here too so the worker can start before transfer-service has created the topic.
    @Bean
    NewTopic transferEvents() {
        return TopicBuilder.name(Topics.TRANSFER_EVENTS).partitions(3).replicas(1).build();
    }

    /** Same partition count as the source: the recoverer publishes to the same partition number. */
    @Bean
    NewTopic transferEventsDlt() {
        return TopicBuilder.name(DLT).partitions(3).replicas(1).build();
    }
}
