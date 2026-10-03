package com.wiselite.risk;

import com.wiselite.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.kafka.KafkaStreamsMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Owns the KafkaStreams instance: topic bootstrap, start/stop with the Spring context, health, metrics. */
@Component
public class RiskStreams implements SmartLifecycle, HealthIndicator {

    private final RiskProperties properties;
    private final MeterRegistry meters;
    private volatile KafkaStreams streams;
    private KafkaStreamsMetrics metrics;

    public RiskStreams(RiskProperties properties, MeterRegistry meters) {
        this.properties = properties;
        this.meters = meters;
    }

    @Override
    public void start() {
        ensureTopics();
        var props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "risk-engine");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, properties.processingGuarantee());
        props.put(StreamsConfig.STATE_DIR_CONFIG, properties.stateDir());
        var kafkaStreams = new KafkaStreams(RiskTopology.build(properties.rules()), props);
        // A bug on one record must not leave a dead thread silently: replace it (the record is retried).
        kafkaStreams.setUncaughtExceptionHandler(e -> StreamThreadExceptionResponse.REPLACE_THREAD);
        metrics = new KafkaStreamsMetrics(kafkaStreams);
        metrics.bindTo(meters);
        kafkaStreams.start();
        streams = kafkaStreams;
    }

    /** Source topic must exist before Streams starts; creating it is idempotent. */
    private void ensureTopics() {
        try (var admin = Admin.create(Map.<String, Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                properties.bootstrapServers()))) {
            var existing = admin.listTopics().names().get(30, TimeUnit.SECONDS);
            var missing = List.of(Topics.TRANSFER_EVENTS, Topics.RISK_ALERTS).stream()
                    .filter(t -> !existing.contains(t))
                    .map(t -> new NewTopic(t, Optional.of(3), Optional.empty()))
                    .toList();
            admin.createTopics(missing).all().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("Cannot create topics", e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Kafka not reachable", e);
        }
    }

    @Override
    public void stop() {
        var s = streams;
        if (s != null) {
            s.close(Duration.ofSeconds(10));
            metrics.close();
            streams = null;
        }
    }

    @Override
    public boolean isRunning() {
        return streams != null;
    }

    public KafkaStreams.State state() {
        var s = streams;
        return s == null ? KafkaStreams.State.NOT_RUNNING : s.state();
    }

    @Override
    public Health health() {
        var state = state();
        var builder = state == KafkaStreams.State.RUNNING || state == KafkaStreams.State.REBALANCING
                ? Health.up() : Health.down();
        return builder.withDetail("state", state.name()).build();
    }
}
