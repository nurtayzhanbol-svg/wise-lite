package com.wiselite.transfer;

import com.wiselite.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class KafkaConfig {

    /** 3 partitions keyed by transfer id; replication 1 because local Kafka has one broker. */
    @Bean
    NewTopic transferEvents() {
        return TopicBuilder.name(Topics.TRANSFER_EVENTS).partitions(3).replicas(1).build();
    }
}
