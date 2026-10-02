package com.aktimetrix.orderprocessmonitor;

import com.aktimetrix.core.api.Conformance;
import com.aktimetrix.core.api.Timeliness;
import com.aktimetrix.core.model.MeasurementInstance;
import com.aktimetrix.core.model.ProcessInstance;
import com.aktimetrix.core.model.StepInstance;
import com.aktimetrix.core.store.MeasurementInstanceStore;
import com.aktimetrix.core.store.ProcessInstanceStore;
import com.aktimetrix.core.store.StepInstanceStore;
import com.aktimetrix.core.service.AlarmScheduler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
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
import java.time.LocalDateTime;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the order monitor end to end, against an embedded Kafka broker and an in-memory MongoDB, with the sample
 * events in {@code events/}: the white paper's example (section 1.1). Order 1234 of a priority customer is created at
 * 09:00, planned by rule, runs through its seven steps with time, distance, fuel, temperature and rating compared
 * with their plans, is delivered within its one-day promise, and is rated the next morning.
 */
@AutoConfigureMetrics  // tests switch metrics export off unless asked
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aktimetrix.alarms.check-interval=PT1H",  // the test fires the alarms itself
        "aktimetrix.monitor.overdue-check-interval=PT1H",
        // the test replays a morning of events against a clock it moves by hand
        "aktimetrix.events.max-future-skew=P1D"
})
@EmbeddedKafka(partitions = 1, topics = {"order-events", "measurement-instance-out-0", "step-instance-out-0",
        "process-instance-out-0", "order-events.dlq"})
class OrderMonitorEndToEndTest {

    private static final MongoServer MONGO = new MongoServer(new MemoryBackend());
    private static final InetSocketAddress MONGO_ADDRESS = MONGO.bind();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri",
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
            return new MutableClock(LocalDateTime.of(2024, 3, 1, 9, 1).toInstant(ZoneOffset.UTC));
        }
    }

    @Autowired
    private EmbeddedKafkaBroker kafka;
    @Autowired
    private MutableClock clock;
    @Autowired
    private AlarmScheduler alarms;
    @Autowired
    private ProcessInstanceStore processInstances;
    @Autowired
    private StepInstanceStore stepInstances;
    @Autowired
    private MeasurementInstanceStore measurementStore;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void monitorsAnOrderFromCreatedToRated() throws Exception {
        // 1. created at 09:00: the order and its seven steps are planned, by duration and by rule
        send("01-order-created.json");
        // every step but the optional rating has a plan; each step is saved on its own, so wait for all of them
        for (String planned : new String[]{"CONFIRM", "PAY", "HANDOVER", "ACCEPT", "TRAVEL", "DELIVERED"}) {
            await(() -> step(planned), step -> step.getPlannedAt() != null);
        }
        assertThat(plannedAt("CONFIRM")).isEqualTo(at(9, 5));
        assertThat(plannedAt("PAY")).isEqualTo(at(9, 15));
        assertThat(plannedAt("HANDOVER")).isEqualTo(at(11, 0));
        assertThat(plannedAt("ACCEPT")).isEqualTo(at(11, 15));
        assertThat(plannedAt("TRAVEL")).isEqualTo(at(12, 0));
        assertThat(plannedAt("DELIVERED")).as("rule: priority customer").isEqualTo(at(12, 15));
        assertThat(processInstance().getPlannedAt()).as("rule: priority, within 1 day")
                .isEqualTo(LocalDateTime.of(2024, 3, 2, 9, 0).toInstant(ZoneOffset.UTC));

        // 2. confirmed and paid on time (payment 5 minutes after plan, within its tolerance)
        send("02-order-confirmed.json");
        send("03-payment-confirmed.json");
        assertThat(await(() -> step("PAY"), done()).getTimeliness()).isEqualTo(Timeliness.ON_TIME);
        assertThat(step("CONFIRM").getTimeliness()).isEqualTo(Timeliness.ON_TIME);

        // 3. at 11:30 the parcel has not been handed over, nor accepted: the alarms at both deadlines fire, both
        // are overdue, and later steps at risk
        clock.set(at(11, 30));
        assertThat(alarms.fireDueAlarms()).isEqualTo(2);
        assertThat(step("HANDOVER").getTimeliness()).isEqualTo(Timeliness.OVERDUE);
        assertThat(step("ACCEPT").getTimeliness()).isEqualTo(Timeliness.OVERDUE);
        assertThat(step("TRAVEL").getTimeliness()).isEqualTo(Timeliness.AT_RISK);

        // 4. handed over at 11:40 and accepted at 11:50: both late
        send("04-handed-to-agent.json");
        send("05-agent-accepted.json");
        assertThat(await(() -> step("ACCEPT"), done()).getTimeliness()).isEqualTo(Timeliness.LATE);
        assertThat(step("HANDOVER").getTimeliness()).isEqualTo(Timeliness.LATE);

        // 5. on the way, a location update: 8 km so far, already over the planned 5 km
        send("06-travel-started.json");
        send("07-location-updated.json");
        MeasurementInstance reading = await(() -> allMeasurements().stream()
                .filter(MeasurementInstance::isInterim).findFirst().orElse(null), m -> true);
        assertThat(reading.getValue()).isEqualTo("8");
        assertThat(reading.getConformance()).isEqualTo(Conformance.OUT_OF_TOLERANCE);

        // 6. arrived at 12:45 after 12 km, using 1.0 litre; delivered at 12:55, the parcel at 40 °C, for €9.50
        send("08-arrived.json");
        send("09-delivered.json");
        await(() -> processInstance(), process -> process.isComplete());
        assertThat(step("TRAVEL").getTimeliness()).isEqualTo(Timeliness.LATE);
        assertThat(step("DELIVERED").getTimeliness()).isEqualTo(Timeliness.LATE);
        assertActual("TRAVEL", "DISTANCE", "12", "7", Conformance.OUT_OF_TOLERANCE);
        assertActual("TRAVEL", "FUEL", "1.0", "0.6", Conformance.OUT_OF_TOLERANCE);
        assertActual("DELIVERED", "TEMPERATURE", "40", "10", Conformance.OUT_OF_TOLERANCE);

        // the order as a whole: delivered within its one-day promise, over its planned cost
        ProcessInstance order = processInstance();
        assertThat(order.getStatus()).isEqualTo("Completed");
        assertThat(order.getTimeliness()).isEqualTo(Timeliness.ON_TIME);
        assertActual(null, "COST", "9.5", "1.5", Conformance.OUT_OF_TOLERANCE);
        // and its fuel per km, from its steps: 1.0 L / 12 km against 0.4 L / 5 km, within 10 %
        MeasurementInstance fuelPerKm = actual(null, "FUEL_PER_KM");
        assertThat(fuelPerKm.getPlannedValue()).isEqualTo("0.08");
        assertThat(fuelPerKm.getConformance()).isEqualTo(Conformance.WITHIN_TOLERANCE);

        // 7. rated the next morning, after the order completed: four stars, within tolerance of five
        send("10-rated.json");
        await(() -> step("RATED"), done());
        assertActual("RATED", "RATING", "4", "-1", Conformance.WITHIN_TOLERANCE);

        // "where is order 1234?"
        JsonNode answer = objectMapper.readTree(
                rest.getForObject("/process-instances?tenant=AA&entityId=1234", String.class));
        assertThat(answer).hasSize(1);
        assertThat(answer.get(0).get("timeliness").asText()).isEqualTo("ON_TIME");
        Map<String, String> timeliness = new HashMap<>();
        answer.get(0).get("steps").forEach(step -> timeliness.put(step.get("stepCode").asText(), step.get("timeliness").asText()));
        assertThat(timeliness).containsEntry("CONFIRM", "ON_TIME").containsEntry("PAY", "ON_TIME")
                .containsEntry("HANDOVER", "LATE").containsEntry("TRAVEL", "LATE").containsEntry("DELIVERED", "LATE");

        // the monitor's metrics are exposed for Prometheus
        String prometheus = rest.getForObject("/actuator/prometheus", String.class);
        assertThat(prometheus)
                .contains("aktimetrix_steps_completed_total{step=\"PAY\",tenant=\"AA\",timeliness=\"ON_TIME\"}")
                .contains("aktimetrix_steps_completed_total{step=\"TRAVEL\",tenant=\"AA\",timeliness=\"LATE\"}")
                .contains("aktimetrix_steps_overdue_total{step=\"HANDOVER\",tenant=\"AA\"}")
                .contains("aktimetrix_measurements_actual_total{conformance=\"OUT_OF_TOLERANCE\",measurement=\"DISTANCE\",tenant=\"AA\"}")
                .contains("aktimetrix_processes_completed_total{process=\"ORDER_DELIVERY\",tenant=\"AA\"}");

        // the API describes itself with OpenAPI, browsable in Swagger UI
        assertThat(rest.getForObject("/v3/api-docs/aktimetrix", String.class))
                .contains("\"/process-instances\"").contains("\"/reference-data/process-definitions\"");
        assertThat(rest.getForEntity("/swagger-ui/index.html", String.class).getStatusCode().is2xxSuccessful()).isTrue();

        // plans, actuals, readings and metrics were published for downstream consumers
        List<String> published = measurementsPublished(Set.of("A TRAVEL DISTANCE 12", "A - FUEL_PER_KM", "A RATED RATING 4"));
        assertThat(published).contains("P TRAVEL DISTANCE 5", "A TRAVEL DISTANCE 8", "A TRAVEL DISTANCE 12",
                "P - COST 8", "A - COST 9.5", "A - FUEL_PER_KM", "A RATED RATING 4");

        // 8. a replayed event changes nothing
        send("01-order-created.json");
        Thread.sleep(2000);
        assertThat(processInstances.findByEntityId("AA", "1234")).hasSize(1);
        assertThat(stepInstances.findByProcessInstance("AA", processInstance().getId())).hasSize(7);
    }

    private static Instant at(int hour, int minute) {
        return LocalDateTime.of(2024, 3, 1, hour, minute).toInstant(ZoneOffset.UTC);
    }

    private static Predicate<StepInstance> done() {
        return step -> "Completed".equals(step.getStatus());
    }

    private Instant plannedAt(String stepCode) {
        return step(stepCode).getPlannedAt();
    }

    private MeasurementInstance actual(String stepCode, String code) {
        return allMeasurements().stream()
                .filter(m -> "A".equals(m.getType()) && !m.isInterim() && code.equals(m.getCode())
                        && Objects.equals(stepCode, m.getStepCode()))
                .findFirst().orElseThrow(() -> new AssertionError("no actual " + code + " for " + stepCode));
    }

    private void assertActual(String stepCode, String code, String value, String deviation, Conformance conformance) {
        MeasurementInstance actual = actual(stepCode, code);
        assertThat(actual.getValue()).as(code).isEqualTo(value);
        assertThat(actual.getDeviation()).as(code).isEqualTo(deviation);
        assertThat(actual.getConformance()).as(code).isEqualTo(conformance);
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
        return processInstances.findByEntityId("AA", "1234").stream().findFirst().orElse(null);
    }

    private List<MeasurementInstance> allMeasurements() {
        ProcessInstance process = processInstance();
        return process == null ? List.of() : measurementStore.findByProcessInstance("AA", process.getId());
    }

    private StepInstance step(String code) {
        ProcessInstance process = processInstance();
        if (process == null) {
            return null;
        }
        return stepInstances.findByProcessInstance("AA", process.getId()).stream()
                .filter(step -> code.equals(step.getStepCode())).findFirst().orElse(null);
    }

    /**
     * "TYPE STEP CODE VALUE" of every measurement published, until the expected ones have arrived; a derived metric is
     * "TYPE - CODE", its value varying in precision.
     */
    private List<String> measurementsPublished(Set<String> expected) throws IOException {
        Map<String, Object> props = KafkaTestUtils.consumerProps("verifier", "false", kafka);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<String> measurements = new ArrayList<>();
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer()) {
            kafka.consumeFromAnEmbeddedTopic(consumer, "measurement-instance-out-0");
            long deadline = System.currentTimeMillis() + 20_000;
            while (!measurements.containsAll(expected) && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(1))) {
                    JsonNode entity = objectMapper.readTree(record.value()).get("entity");
                    String step = entity.get("stepCode").isNull() ? "-" : entity.get("stepCode").asText();
                    boolean derived = !entity.get("derivedFrom").isNull();
                    measurements.add(entity.get("type").asText() + " " + step + " " + entity.get("code").asText()
                            + (derived ? "" : " " + entity.get("value").asText()));
                }
            }
        }
        return measurements;
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

        MutableClock(Instant start) {
            set(start);
        }

        void set(Instant time) {
            this.instant = time;
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
