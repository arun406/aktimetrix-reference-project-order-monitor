# Order Monitor: an Aktimetrix reference project

A complete, runnable example of [Aktimetrix](https://github.com/arun406/aktimetrix): the order delivery process of
the [white paper](https://github.com/arun406/aktimetrix/blob/main/docs/white-paper.md#11-a-worked-example-order-delivery), end to end. For every
order it plans, from rules, what should happen, at the level of the order and of each of its steps. It then compares
what does happen with that plan, in every dimension it measures: time, distance, fuel, temperature, cost and the
customer's rating.

<p align="center">
  <img src="https://raw.githubusercontent.com/arun406/aktimetrix/main/img/plan-vs-actual.svg" alt="Plan versus actual for order 1234" width="100%">
</p>

## The process

An order is created; that event starts an `ORDER_DELIVERY` process for the order, and rules plan it. A priority
customer's order is due within one day, at a cost of €8, and each step has its own plan:

| Step | Completed by | Plan | Tolerance |
|---|---|---|---|
| `CONFIRM` Order confirmed | `ORDER_CONFIRMED_EVENT` | within 5 min | |
| `PAY` Payment confirmed | `PAYMENT_CONFIRMED_EVENT` | within 15 min | 5 min |
| `HANDOVER` Handed to the delivery agent | `HANDED_TO_AGENT_EVENT` | within 2 h | |
| `ACCEPT` Delivery agent accepted | `AGENT_ACCEPTED_EVENT` | within 2 h 15 min | |
| `TRAVEL` Travel to the customer | started by `TRAVEL_STARTED_EVENT`, ended by `ARRIVED_EVENT`; progress on `LOCATION_UPDATED_EVENT` | within 3 h; 5 km; 0.4 L of fuel | 20 %; 25 %; only higher is worse |
| `DELIVERED` Delivered | `ORDER_DELIVERED_EVENT` | by rule: priority within 3 h 15 min, others 2 days; the parcel at 30 °C | 5 °C; only higher is worse |
| `RATED` Rated by the customer (optional) | `ORDER_RATED_EVENT` | 5 stars | 1 star; only lower is worse |
| *The order* `ORDER_DELIVERY` | ended by its last mandatory step | by rule: priority within 1 day, others 3; cost €8; fuel per km = FUEL / DISTANCE | 10 %; 10 % |

The Aktimetrix model needs only a **message broker** and a **state store** (see the
[white paper](https://github.com/arun406/aktimetrix/blob/main/docs/white-paper.md#52-infrastructure-contract)). This example uses the reference
implementation's bindings:

| Role in the model | In this example |
|---|---|
| Message broker | Apache Kafka: inbound channel `order-events`, and the outbound channels |
| State store | MongoDB: definitions, process, step and measurement instances, and the outbox |
| Runtime | Spring Boot application with `aktimetrix-core`, `aktimetrix-store-mongodb` and `aktimetrix-broker-kafka` |

The commands below are therefore Kafka- and MongoDB-specific. The definitions and process handler are not:
they stay the same with any other broker or store. To keep the state in PostgreSQL, replace `aktimetrix-store-mongodb`
with `aktimetrix-store-jdbc` and the PostgreSQL driver; to use RabbitMQ, replace `aktimetrix-broker-kafka` with
`aktimetrix-broker-rabbitmq`. Then change the connection settings in `application.yml`.

## What's in it

The whole application is one definitions class, written with the Aktimetrix Java DSL, and one process handler.
Everything else comes from the framework.

| File | Purpose |
|---|---|
| [`OrderDeliveryDefinitions`](src/main/java/com/aktimetrix/orderprocessmonitor/definitions/OrderDeliveryDefinitions.java) | The `ORDER_DELIVERY` process, in the Java DSL: started by `ORDER_CREATED_EVENT`, cancelled by `ORDER_CANCELLED_EVENT`; its seven steps with the events that complete them or report their progress, their planned times and tolerances; the measurements compared with their plans; the fuel-per-km metric; and the two planning rules, as lambdas: the whole order within 1 day for priority customers (3 for others), and `DELIVERED` within 3 h 15 min (2 days). |
| [`examples/order-delivery.yaml`](src/main/resources/examples/order-delivery.yaml) | The same definitions as a YAML file, for comparison; not loaded. A test checks that it stays identical to the Java definitions. |
| [`OrderProcessor`](src/main/java/com/aktimetrix/orderprocessmonitor/processhandler/OrderProcessor.java) | Decides what to remember about an order: whether the customer is a priority customer, and when the order was created. |
| [`application.yml`](src/main/resources/application.yml) | Connections to the broker (Kafka) and state store (MongoDB), and the inbound channel `order-events`. |
| [`events/`](events) | The ten events of order `1234`, in order. |
| [`OrderMonitorEndToEndTest`](src/test/java/com/aktimetrix/orderprocessmonitor/OrderMonitorEndToEndTest.java) | The whole story below, as a test. |

The definitions are saved to the state store at startup, so there is no data to import by hand.

## Run it

You need **JDK 17+** and **Docker**.

```bash
# 1. Build the framework (it is not on Maven Central yet)
git clone https://github.com/arun406/aktimetrix.git
(cd aktimetrix && ./mvnw install -DskipTests)

# 2. Start the broker (Kafka) and state store (MongoDB), then the monitor
git clone https://github.com/arun406/aktimetrix-reference-project-order-monitor.git
cd aktimetrix-reference-project-order-monitor
docker compose up -d
./mvnw spring-boot:run
```

In a second terminal, send the order's events one at a time, and after each one ask where the order is:

```bash
send() { docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
           --bootstrap-server localhost:9092 --topic order-events < "events/$1"; }
where() { curl -s 'http://localhost:8080/process-instances?tenant=AA&entityId=1234'; }

send 01-order-created.json; where
send 02-order-confirmed.json; where
# … and so on, up to
send 10-rated.json; where
```

The API is described with OpenAPI: browse it in Swagger UI at <http://localhost:8080/swagger-ui.html>, or read the
description itself at <http://localhost:8080/v3/api-docs/aktimetrix>.

## What happens

| Event | At | What Aktimetrix records |
|---|---|---|
| `01-order-created` | 09:00 | The order and its seven steps, with their plans: `CONFIRM` 09:05 … `DELIVERED` 12:15 by rule; the order due by 09:00 tomorrow, by rule; 5 km, 0.4 L, 30 °C, 5 ★, €8. |
| `02-order-confirmed` | 09:02 | `CONFIRM` completed, 3 min early: `ON_TIME`. |
| `03-payment-confirmed` | 09:20 | `PAY` completed, 5 min after plan, within its 5 min tolerance: `ON_TIME`. |
| *(no event)* | 11:00 | `HANDOVER` passes its deadline without its event: `OVERDUE`, and the steps after it are forecast late: `AT_RISK`. |
| `04-handed-to-agent` | 11:40 | `HANDOVER` completed, 40 min late: `LATE`. |
| `05-agent-accepted` | 11:50 | `ACCEPT` completed, 35 min late: `LATE`. |
| `06-travel-started` | 11:55 | `TRAVEL` started. |
| `07-location-updated` | 12:20 | An interim reading: 8 km so far, already over the planned 5 km: outside tolerance. |
| `08-arrived` | 12:45 | `TRAVEL` completed, 45 min late; 12 km instead of 5, 1.0 L instead of 0.4: outside tolerance. |
| `09-delivered` | 12:55 | `DELIVERED` completed, 40 min late; parcel at 40 °C instead of 30: outside tolerance. The order completes, **on time** against its one-day promise, at €9.50 instead of €8 (outside tolerance), with 0.083 L/km of fuel against 0.08 planned (within tolerance). |
| `10-rated` | next day 08:00 | `RATED`, after the order completed: 4 ★ instead of 5, within tolerance. |

Four steps ran late, yet the order kept its promise: the steps and the order are judged separately.

When the order is planned, an alarm is set at each step's deadline. On a live run due alarms are fired every 5 seconds
(`aktimetrix.alarms.check-interval`). The sample events are dated 1 March 2024, so their deadlines are already in the
past: steps show `OVERDUE` a few seconds after the order is created, until their events arrive and they are judged
`ON_TIME` or `LATE` against the plan.

The query returns the order with its steps (abbreviated):

```json
[{
  "processCode": "ORDER_DELIVERY", "entityId": "1234", "status": "Completed",
  "plannedAt": "2024-03-02T09:00:00", "endedAt": "2024-03-01T12:55:00", "timeliness": "ON_TIME",
  "steps": [
    { "stepCode": "CONFIRM", "status": "Completed", "plannedAt": "2024-03-01T09:05:00", "actualAt": "2024-03-01T09:02:00", "timeliness": "ON_TIME" },
    { "stepCode": "TRAVEL",  "status": "Completed", "plannedAt": "2024-03-01T12:00:00", "actualAt": "2024-03-01T12:45:00", "timeliness": "LATE" },
    …
  ]
}]
```

Every result is also published to the outbound channels, Kafka topics in this example, for dashboards and alerting:

| Channel | Messages |
|---|---|
| `process-instance-out-0` | the order was created, completed, cancelled or became overdue |
| `step-instance-out-0` | a step was created, planned, started, completed, skipped or cancelled, or became at risk or overdue |
| `measurement-instance-out-0` | a planned or actual value (or an interim reading, or the fuel-per-km metric), with its plan, deviation and whether it is within tolerance |

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic measurement-instance-out-0 --from-beginning
```

Results reach the broker through an outbox in the state store, so if the broker is briefly down they are sent once
it's back.

### Metrics

Spring Boot Actuator exposes the monitor's metrics for Prometheus:

```bash
curl -s http://localhost:8080/actuator/prometheus | grep -E 'aktimetrix_(steps_completed|measurements_actual)'
# aktimetrix_steps_completed_total{step="PAY",tenant="AA",timeliness="ON_TIME",} 1.0
# aktimetrix_steps_completed_total{step="TRAVEL",tenant="AA",timeliness="LATE",} 1.0
# aktimetrix_measurements_actual_total{conformance="OUT_OF_TOLERANCE",measurement="DISTANCE",tenant="AA",} 1.0
```

## Test it

```bash
./mvnw test
```

[`OrderMonitorEndToEndTest`](src/test/java/com/aktimetrix/orderprocessmonitor/OrderMonitorEndToEndTest.java) runs
the story above against an embedded broker (Kafka) and an in-memory state store (MongoDB). It moves a clock forward
to see `HANDOVER` become overdue and later steps at risk, and checks every plan, actual, deviation and tolerance, the
order's timeliness, cost and fuel per km, the query API, the Prometheus metrics and the published measurements. No
Docker is needed; CI runs it on every pull request.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `MONGODB_URI` | `mongodb://localhost:27017/order-monitor` | MongoDB connection string |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bootstrap servers |
| `SPRING_PROFILES_ACTIVE` | none | Set to `confluent` to connect to Confluent Cloud over SASL_SSL |
| `KAFKA_API_KEY` / `KAFKA_API_SECRET` | none | Confluent Cloud API key and secret (only with the `confluent` profile) |

No credentials are committed: see [`.env.example`](.env.example). All `aktimetrix.*` settings are described in the
framework's [configuration reference](https://github.com/arun406/aktimetrix/blob/main/docs/configuration.md).

## Make it yours

To monitor a different process, change the definitions:

1. Describe the process and its steps in `OrderDeliveryDefinitions`: for each step, the event that completes it
   (`on`), or the events that start and end it (`startsOn`, `endsOn`); for the process and each step, the
   measurements that matter, with a planned value (`measure`), a duration (`within`) or a rule (`planTime`, `plan`),
   and a tolerance.
2. Optionally, write a `@ProcessHandler` to choose the metadata the rules need.

```java
Definitions.tenant("AA")
        .process("ORDER_DELIVERY", order -> order
                .entityType("com.ecom.order")
                .startsOn("ORDER_CREATED_EVENT")
                .step("PAY", step -> step.on("PAYMENT_CONFIRMED_EVENT").within("PT15M").tolerance("PT5M"))
                .step("DELIVERED", step -> step.on("ORDER_DELIVERED_EVENT")
                        .planTime(s -> metadataTime(s, "createdAt").plus(Duration.ofHours(4)))))
        .build();
```

Prefer to keep definitions out of the code? Put a YAML file like
[`examples/order-delivery.yaml`](src/main/resources/examples/order-delivery.yaml) under `src/main/resources/aktimetrix/`
instead, and write each rule as a `@Measurement` meter. The DSL and the file formats are described in the framework's
[getting-started guide](https://github.com/arun406/aktimetrix/blob/main/docs/getting-started.md#the-same-monitor-in-java-or-yaml).

If your systems already publish events in their own format, keep it and add an `EventMapper`: see
[Extending Aktimetrix](https://github.com/arun406/aktimetrix/blob/main/docs/extending.md#accepting-your-own-event-format).

## License

This project is released under the [Apache License 2.0](./LICENSE).
