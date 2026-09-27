package com.aktimetrix.orderprocessmonitor;

import com.aktimetrix.core.api.Timeliness;
import com.aktimetrix.core.model.ProcessInstance;
import com.aktimetrix.core.model.StepInstance;
import com.aktimetrix.core.repository.ProcessInstanceRepository;
import com.aktimetrix.core.repository.StepInstanceRepository;
import com.aktimetrix.core.service.OverdueStepMonitor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.metrics.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the order monitor end to end, against an embedded Kafka broker and an in-memory MongoDB, with the sample
 * events in {@code events/}: order 1234 is placed, ships on time, is not delivered by its planned time, and is
 * delivered late.
 */
@AutoConfigureMetrics  // tests switch metrics export off unless asked
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aktimetrix.monitor.overdue-check-interval=PT1H"  // the test runs the overdue check itself
})
@EmbeddedKafka(partitions = 1, topics = {"order-events", "measurement-instance-out-0", "step-instance-out-0",
        "process-instance-out-0"})
class OrderMonitorEndToEndTest {

    private static final MongoServer MONGO = new MongoServer(new MemoryBackend());
    private static final InetSocketAddress MONGO_ADDRESS = MONGO.bind();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri",
                () -> "mongodb://localhost:" + MONGO_ADDRESS.getPort() + "/order-monitor");
        registry.add("spring.kafka.properties.bootstrap.servers", () -> "${spring.embedded.kafka.brokers}");
        registry.add("spring.cloud.stream.kafka.binder.brokers", () -> "${spring.embedded.kafka.brokers}");
    }

    @AfterAll
    static void stopMongo() {
        MONGO.shutdown();
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        MutableClock clock() {
            return new MutableClock(LocalDateTime.of(2022, 5, 22, 23, 50));
        }
    }

    @Autowired
    private EmbeddedKafkaBroker kafka;
    @Autowired
    private MutableClock clock;
    @Autowired
    private OverdueStepMonitor overdueStepMonitor;
    @Autowired
    private ProcessInstanceRepository processInstances;
    @Autowired
    private StepInstanceRepository stepInstances;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void monitorsAnOrderFromPlacedToDelivered() throws Exception {
        // 1. the order is placed: the process starts, PLACE completes, SHIP and DELIVER are planned
        send("order-placed.json");
        StepInstance ship = await(() -> step("SHIP"), step -> step.getPlannedAt() != null);
        assertThat(ship.getPlannedAt()).isEqualTo(LocalDateTime.of(2022, 5, 23, 1, 46));
        StepInstance deliver = await(() -> step("DELIVER"), step -> step.getPlannedAt() != null);
        assertThat(deliver.getPlannedAt()).isEqualTo(LocalDateTime.of(2022, 5, 23, 9, 46));
        StepInstance place = await(() -> step("PLACE"), step -> "Completed".equals(step.getStatus()));
        assertThat(place.getActualAt()).isEqualTo(LocalDateTime.of(2022, 5, 22, 23, 46));

        // 2. at 01:00 nothing is overdue yet
        clock.set(LocalDateTime.of(2022, 5, 23, 1, 0));
        assertThat(overdueStepMonitor.checkOverdueSteps()).isEmpty();

        // 3. the order ships at 01:30, before its planned 01:46
        send("order-shipped.json");
        ship = await(() -> step("SHIP"), step -> "Completed".equals(step.getStatus()));
        assertThat(ship.getActualAt()).isEqualTo(LocalDateTime.of(2022, 5, 23, 1, 30));
        assertThat(ship.getTimeliness()).isEqualTo(Timeliness.ON_TIME);

        // 4. at 10:00 there is no delivery yet, and it was planned for 09:46
        clock.set(LocalDateTime.of(2022, 5, 23, 10, 0));
        assertThat(overdueStepMonitor.checkOverdueSteps()).extracting(StepInstance::getStepCode).containsExactly("DELIVER");
        assertThat(step("DELIVER").getTimeliness()).isEqualTo(Timeliness.OVERDUE);

        // 5. the order is delivered at 10:30: late, and the process is complete
        send("order-delivered.json");
        deliver = await(() -> step("DELIVER"), step -> "Completed".equals(step.getStatus()));
        assertThat(deliver.getTimeliness()).isEqualTo(Timeliness.LATE);
        assertThat(processInstance().isComplete()).isTrue();

        // "where is order 1234?"
        JsonNode answer = objectMapper.readTree(
                rest.getForObject("/process-instances?tenant=AA&entityId=1234", String.class));
        assertThat(answer).hasSize(1);
        assertThat(answer.get(0).get("status").asText()).isEqualTo("Completed");
        Map<String, String> timeliness = new HashMap<>();
        answer.get(0).get("steps").forEach(step -> timeliness.put(step.get("stepCode").asText(), step.get("timeliness").asText()));
        assertThat(timeliness).containsEntry("SHIP", "ON_TIME").containsEntry("DELIVER", "LATE");

        // the monitor's metrics are exposed for Prometheus
        String prometheus = rest.getForObject("/actuator/prometheus", String.class);
        assertThat(prometheus)
                .contains("aktimetrix_steps_completed_total{step=\"SHIP\",tenant=\"AA\",timeliness=\"ON_TIME\",}")
                .contains("aktimetrix_steps_completed_total{step=\"DELIVER\",tenant=\"AA\",timeliness=\"LATE\",}")
                .contains("aktimetrix_steps_overdue_total{step=\"DELIVER\",tenant=\"AA\",}")
                .contains("aktimetrix_processes_completed_total{process=\"ORDER_DELIVERY\",tenant=\"AA\",}");

        // planned and actual measurements were published for downstream consumers
        List<String> published = measurementsPublished(5);
        assertThat(published).contains("P SHIP 2022-05-23T01:46", "P DELIVER 2022-05-23T09:46",
                "A PLACE 2022-05-22T23:46", "A SHIP 2022-05-23T01:30", "A DELIVER 2022-05-23T10:30");

        // 6. a replayed event changes nothing
        send("order-placed.json");
        Thread.sleep(2000);
        assertThat(processInstances.findAll()).hasSize(1);
        assertThat(stepInstances.findAll()).hasSize(3);
    }

    private void send(String eventFile) throws IOException {
        String event = Files.readString(Path.of("events", eventFile)).trim();
        Map<String, Object> props = KafkaTestUtils.producerProps(kafka);
        KafkaTemplate<String, String> template = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new StringSerializer()));
        template.send("order-events", "1234", event);
        template.flush();
    }

    private ProcessInstance processInstance() {
        return processInstances.findAll().stream().findFirst().orElse(null);
    }

    private StepInstance step(String code) {
        ProcessInstance process = processInstance();
        if (process == null) {
            return null;
        }
        return stepInstances.findByTenantAndStepCodeAndProcessInstanceId("AA", code, process.getId())
                .stream().findFirst().orElse(null);
    }

    private List<String> measurementsPublished(int expected) throws IOException {
        Map<String, Object> props = KafkaTestUtils.consumerProps("verifier", "false", kafka);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<String> measurements = new ArrayList<>();
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer()) {
            kafka.consumeFromAnEmbeddedTopic(consumer, "measurement-instance-out-0");
            long deadline = System.currentTimeMillis() + 10_000;
            while (measurements.size() < expected && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : KafkaTestUtils.getRecords(consumer, 1000)) {
                    JsonNode entity = objectMapper.readTree(record.value()).get("entity");
                    measurements.add(entity.get("type").asText() + " " + entity.get("stepCode").asText() + " "
                            + entity.get("value").asText());
                }
            }
        }
        return measurements.stream().distinct().collect(Collectors.toList());
    }

    private static <T> T await(Supplier<T> value, Predicate<T> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        T current = value.get();
        while ((current == null || !condition.test(current)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            current = value.get();
        }
        assertThat(current).as("condition not reached in time").isNotNull().matches(condition);
        return current;
    }

    /**
     * A clock the test can move forward, to see steps become overdue.
     */
    static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(LocalDateTime start) {
            set(start);
        }

        void set(LocalDateTime time) {
            this.instant = time.toInstant(ZoneOffset.UTC);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
