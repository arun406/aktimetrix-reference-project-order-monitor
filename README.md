# Order Monitor: an Aktimetrix reference project

A complete, runnable example of [Aktimetrix](https://github.com/arun406/aktimetrix): it monitors the delivery of
e-commerce orders and tells you, for every order, whether each milestone happened **on time**, **late**, or is
**overdue**.

<p align="center">
  <img src="https://raw.githubusercontent.com/arun406/aktimetrix/main/img/order-timeline.svg" alt="Planned and actual timeline of order 1234" width="100%">
</p>

The business rule it monitors: an order is **placed**, should **ship within 2 hours**, and should be **delivered
within 10 hours**.

## What's in it

The whole application is some JSON, one process handler and two meters. Everything else comes from the framework.

| File | Purpose |
|---|---|
| [`aktimetrix/process-definitions.json`](src/main/resources/aktimetrix/process-definitions.json) | The `ORDER_DELIVERY` process: started by `ORDER_PLACED_EVENT`, with steps `PLACE` → `SHIP` → `DELIVER`. |
| [`aktimetrix/step-definitions.json`](src/main/resources/aktimetrix/step-definitions.json) | Each step: the event that completes it, and whether it has a planned `TIME`. |
| [`OrderProcessor`](src/main/java/com/aktimetrix/orderprocessmonitor/processhandler/OrderProcessor.java) | Decides what to remember about an order: the steps keep `orderedOn` for the meters. |
| [`OrderShippedPlanTimeMeter`](src/main/java/com/aktimetrix/orderprocessmonitor/meter/OrderShippedPlanTimeMeter.java) | Plans `SHIP` at *ordered + 2 h*. |
| [`OrderDeliveredPlanTimeMeter`](src/main/java/com/aktimetrix/orderprocessmonitor/meter/OrderDeliveredPlanTimeMeter.java) | Plans `DELIVER` at *ordered + 10 h*. |
| [`application.yml`](src/main/resources/application.yml) | MongoDB, Kafka, and the inbound topic `order-events`. |
| [`eventhandler/`](src/main/java/com/aktimetrix/orderprocessmonitor/eventhandler) | *Optional.* Takes each step's actual time from the order (`orderedOn`, `shippedAt`, `deliveredAt`) instead of the event envelope. Without them, Aktimetrix handles every event itself. |
| [`events/`](events) | Sample events for order `1234`. |
| [`OrderMonitorEndToEndTest`](src/test/java/com/aktimetrix/orderprocessmonitor/OrderMonitorEndToEndTest.java) | The whole story below, as a test. |

The definitions are loaded from the classpath at startup, so there is no data to import by hand.

## Run it

You need **JDK 11+** and **Docker**.

```bash
# 1. Build the framework (it is not on Maven Central yet)
git clone https://github.com/arun406/aktimetrix.git
(cd aktimetrix && ./mvnw install -DskipTests)

# 2. Start Kafka and MongoDB, then the monitor
git clone https://github.com/arun406/aktimetrix-reference-project-order-monitor.git
cd aktimetrix-reference-project-order-monitor
docker compose up -d
./mvnw spring-boot:run
```

In a second terminal, send the order's events one at a time:

```bash
send() { docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
           --bootstrap-server localhost:9092 --topic order-events < "events/$1"; }

send order-placed.json      # placed at 23:46
send order-shipped.json     # shipped at 01:30
send order-delivered.json   # delivered at 10:30
```

After each one, ask where the order is:

```bash
curl -s 'http://localhost:8080/process-instances?tenant=AA&entityId=1234'
```

## What happens

| After | `PLACE` | `SHIP` (planned 01:46) | `DELIVER` (planned 09:46) | Process |
|---|---|---|---|---|
| `order-placed.json` | Completed, 23:46 | Created | Created | Created |
| `order-shipped.json` | Completed | Completed, 01:30, **ON_TIME** | Created | Created |
| `order-delivered.json` | Completed | Completed, **ON_TIME** | Completed, 10:30, **LATE** | **Completed** |

When a step's planned time passes and its event hasn't arrived, the overdue monitor marks it **OVERDUE**. It checks
every minute (`aktimetrix.monitor.overdue-check-interval`). The sample events are dated 2022, so on a live run their
planned times are already in the past: `SHIP` and `DELIVER` show `OVERDUE` about a minute after the order is
placed, until their events arrive and they are judged `ON_TIME` or `LATE` against the plan.

The query returns the process instance with its steps (abbreviated):

```json
[{
  "processCode": "ORDER_DELIVERY", "entityId": "1234", "status": "Completed", "complete": true,
  "metadata": { "orderId": "1234", "customerId": "1", "orderTotal": 100.0, "orderCurrency": "USD" },
  "steps": [
    { "stepCode": "PLACE",   "status": "Completed", "actualAt": "2022-05-22T23:46:00", "plannedAt": null,                  "timeliness": null },
    { "stepCode": "SHIP",    "status": "Completed", "actualAt": "2022-05-23T01:30:00", "plannedAt": "2022-05-23T01:46:00", "timeliness": "ON_TIME" },
    { "stepCode": "DELIVER", "status": "Completed", "actualAt": "2022-05-23T10:30:00", "plannedAt": "2022-05-23T09:46:00", "timeliness": "LATE" }
  ]
}]
```

Everything is also published to Kafka for dashboards and alerting:

| Topic | Messages |
|---|---|
| `process-instance-out-0` | a process was created |
| `step-instance-out-0` | a step was `CREATED`, `COMPLETED`, or became `AT_RISK` or `OVERDUE` |
| `measurement-instance-out-0` | a planned (`P`) or actual (`A`) `TIME` |

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic step-instance-out-0 --from-beginning
```

Events reach Kafka through an outbox in MongoDB, so if Kafka is briefly down they are sent once it's back.

### Metrics

Spring Boot Actuator exposes the monitor's metrics for Prometheus:

```bash
curl -s http://localhost:8080/actuator/prometheus | grep aktimetrix_steps_completed
# aktimetrix_steps_completed_total{step="SHIP",tenant="AA",timeliness="ON_TIME",} 1.0
# aktimetrix_steps_completed_total{step="DELIVER",tenant="AA",timeliness="LATE",} 1.0
```

## Test it

```bash
./mvnw test
```

[`OrderMonitorEndToEndTest`](src/test/java/com/aktimetrix/orderprocessmonitor/OrderMonitorEndToEndTest.java) runs
the story above against an embedded Kafka broker and an in-memory MongoDB, with a clock it moves forward to see
`DELIVER` become overdue, and checks the Prometheus metrics. No Docker is needed; CI runs it on every pull request.

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

To monitor a different process, change the JSON and the meters:

1. Describe the process and its steps in `aktimetrix/process-definitions.json` and `aktimetrix/step-definitions.json`.
   For each step, list the event that completes it, and add `{ "measurementCode": "TIME", "type": "P" }` if it has
   a deadline.
2. Write one `@Measurement(code = "TIME", stepCode = "…")` meter per planned step.
3. Optionally, write a `@ProcessHandler` to choose the metadata the meters need.

## License

This project is released under the [Apache License 2.0](./LICENSE).
