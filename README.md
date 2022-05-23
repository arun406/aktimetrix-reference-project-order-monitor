To manually initialize the project:

Navigate to https://start.spring.io. This service pulls in all the dependencies you need for an application and does most of the setup for you.

Choose either Gradle or Maven and the language you want to use. This guide assumes that you chose Java.

Click Dependencies and add the following dependencies
1. Spring Web.
2. Lombok
3. Cloud stream
4. Spring Data MongoDB
5. Spring for Apache Kafka
6. Spring for Apache Kafka Streams

Add the following details in Project Metadata.
1. Group name as `com.aktimetrix`
2. Artifact as `order-process-monitor`
3. Name as `Reference project for Aktimetrix`
4. Package name as `com.aktimetrix.orderprocessmonitor`

Click Generate.

Download the resulting ZIP file, which is an archive of a web application that is configured with your choices.

Extract the ZIP file and import the project in your favourate IDE.


Note: You can also fork the project from Github and open it in your IDE or other editor.

1. Adding the Aktimetrix core dependency.

```
<dependency>
    <groupId>com.aktimetrix</groupId>
    <artifactId>aktimetrix-core</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

2. Add the below configuration in application.properties (.yml)

```properties

# database configuration
spring.data.mongodb.database=svm
spring.data.mongodb.uri={{ MONGODB_URI}}
spring.jackson.serialization.write-dates-as-timestamps=false

#message broker configuration if you are using confluent cloud.
spring.kafka.properties.sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule   required username='{{ CLUSTER_API_KEY }}'   password='{{CLUSTER_API_SECRET}}';
spring.kafka.properties.sasl.mechanism=PLAIN
spring.kafka.properties.session.timeout.ms=45000
spring.kafka.properties.security.protocol=SASL_SSL
spring.kafka.properties.bootstrap.servers={{ BROKER_ENDPOINT  }}

#application configuration
spring.cloud.stream.function.bindings.processor-in-0=event-processor
spring.cloud.stream.function.bindings.measure-in-0=step-event-processor
spring.cloud.stream.function.definition=processor;measure
spring.cloud.stream.bindings.event-processor.group=processor.group.0
spring.cloud.stream.bindings.event-processor.destination=order-event-topic
spring.cloud.stream.bindings.step-event-processor.group=step.group.0
spring.cloud.stream.bindings.step-event-processor.destination=step-instance-out-0
spring.cloud.stream.source=process-instance;step-instance;measurement-instance
spring.cloud.stream.kafka.bindings.measurement-instance-out-0.producer.configuration.[key.serializer]=org.apache.kafka.common.serialization.StringSerializer
spring.cloud.stream.kafka.bindings.process-instance-out-0.producer.configuration.[key.serializer]=org.apache.kafka.common.serialization.StringSerializer
spring.cloud.stream.kafka.bindings.step-instance-out-0.producer.configuration.[key.serializer]=org.apache.kafka.common.serialization.StringSerializer
spring.cloud.stream.kafka.bindings.step-event-processor.consumer.enableDlq=true
spring.cloud.stream.kafka.bindings.step-event-processor.consumer.dlqName=input-topic-dlq
logging.level.com.aktimetrix=DEBUG
```
In the above configuration replace the MONGODB_URI with the mongo db uri where it is running.
And if Kafka message broker is running on you local machine (`localhost:9092`), you can remove the #message broker configuration from the above file.

3. Add the aktimetrix core package ( `"com.aktimetrix.core"`) to application component scan as shown below.
Final Application file looks like below. 
```java
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@ComponentScan(basePackages = {"com.aktimetrix.orderprocessmonitor", "com.aktimetrix.core"})
@SpringBootApplication
public class OrderProcessMonitorApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderProcessMonitorApplication.class, args);
    }
}
```
4. Run the Application.
If you use Maven, run the following command in a terminal window (in the complete) directory:
```
   ./mvnw spring-boot:run
```
5. Create the reference data. 
    Connect to the Mongo DB's svm database and upload the reference data. Download the sample data from [this](./src/main/resources/) location.
6. Alternatively, reference data can be created using executing the given postman collection. Download the postman collection from [this](./src/main/resources/) location.
7. Reference data creates `ORDER_DELIVERY` process as collection of  `PLACE, SHIP and DELIVER` steps and each step contains `TIME` measurement type as planned measurements.
Order delivery process will be initiated by the `ORDER_PLACED_EVENT`.
8. Let's define a order placed event `ORDER_PLACED_EVENT`.
```json
{
  "tenantKey": "AA",
  "eventId": "51541182-81fa-4727-afd5-114acdf086b1",
  "eventName": "order placed event",
  "eventType": "ORDER",
  "eventCode": "ORDER_PLACED_EVENT",
  "eventTime": "2015-11-18T00:00:00.000+0200",
  "eventUTCTime": "2015-11-18 00:00:00",
  "source": "AA",
  "entityId": "1234",
  "entityType": "com.ecom.order",
  "entity": {
    "orderId": "1234",
    "orderedOn": "2022-05-22 23:46:00",
    "customerId": "1",
    "orderTotal": 100,
    "orderCurrency": "USD",
    "productId": "1",
    "quantity": 2,
    "shippingAddress": "Mr John Smith 132, My Street, Kingston, New York 12401 United States"
  },
  "eventDetails": {
   }
}
```
The above event structure is self-explanatory. Important property to note is `eventCode`. We will be using in the event handler.

10. Create the Order POJO. Sample is given below.
```java
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class Order implements Serializable {
    String orderId;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    LocalDateTime orderedOn;
    String customerId;
    double orderTotal;
    String orderCurrency;
    String productId;
    int quantity;
    String shippingAddress;
}
```
9. Create `ORDER_PLACED_EVENT` handler by extending `AbstractEventHandler` and annotating the class with `@EventHandler`. When Order is placed in the business application, this event handler will be invoked to create the process instant and step instances for each order.
The event handler creation is simple as below.
```java
    @Component
    @EventHandler(eventType = "ORDER_PLACED_EVENT")
    public class OrderPlacedEventHandler extends AbstractEventHandler {
    } 
```
`eventType` in the `@EventHandler` should match the `eventCode` from event published.
10. Create the Process Handler for Order Delivery Process. 
Process Handler should extend the `AbstractProcessHandler` and it should be annotated with `@ProcessHandler`.
```java
import com.aktimetrix.core.api.Constants;
import com.aktimetrix.core.api.Context;
import com.aktimetrix.core.impl.AbstractProcessor;
import com.aktimetrix.core.stereotypes.ProcessHandler;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ProcessHandler(processType = "ORDER_DELIVERY")
public class OrderProcessor extends AbstractProcessor {
    @Override
    protected Map<String, Object> getStepMetadata(Context context) {
        HashMap<String, Object> map = new HashMap<>();
        LinkedHashMap<String, Object> entity = (LinkedHashMap<String, Object>) context.getProperty(Constants.ENTITY);

        String orderedOn = (String) entity.get("orderedOn");
        LocalDateTime time = LocalDateTime.parse(orderedOn, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        map.put("orderedOn", time);
        map.put("orderedId", (String) entity.get("orderId"));
        return map;
    }

    @Override
    protected Map<String, Object> getProcessMetadata(Context context) {
        return (LinkedHashMap) context.getProperty(Constants.ENTITY);
    }
}
```
`processType` in `@ProcessHandler` should match the `processCode` of the process definition. 
metadata for the process and step instances can be added by overriding the `getStepMetadata` and `getProcessMetadata` methods.
11. Create the `Meters` for the generating the `plan measurements` when order placed event is consumed by the application.
12. Create the Shipped step Plan Time Meter as below. Any `Meter` class should be annotated with @Measurement annotation and extends the `AbstractMeter` class. 
```java
import com.aktimetrix.core.meter.impl.AbstractMeter;
import com.aktimetrix.core.model.StepInstance;
import com.aktimetrix.core.stereotypes.Measurement;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Component
@Measurement(code = "TIME", stepCode = "SHIP")
public class OrderShippedPlanTimeMeter extends AbstractMeter {
    @Override
    protected String getMeasurementUnit(String tenant, StepInstance step) {
        return "TIMESTAMP";
    }

    @Override
    protected String getMeasurementValue(String tenant, StepInstance step) {
        String orderedOn = (String) step.getMetadata().get("orderedOn");
        LocalDateTime time = LocalDateTime.parse(orderedOn, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

        return String.valueOf(time.plus(2, ChronoUnit.HOURS));
    }
} 
```
`code` and `stepCode` properties of Measurement annotation should reflect the measurement defined at step definition.
Here we are assuming plan time for order ship step should be 2 hours from the order placed time. 

13. Create the Delivery step Plan Time Meter as below.
```java

import com.aktimetrix.core.meter.impl.AbstractMeter;
import com.aktimetrix.core.model.StepInstance;
import com.aktimetrix.core.stereotypes.Measurement;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Component
@Measurement(code = "TIME", stepCode = "DELIVER")
public class OrderDeliveredPlanTimeMeter extends AbstractMeter {
    @Override
    protected String getMeasurementUnit(String tenant, StepInstance step) {
        return "TIMESTAMP";
    }

    @Override
    protected String getMeasurementValue(String tenant, StepInstance step) {
        String orderedOn = (String) step.getMetadata().get("orderedOn");
        LocalDateTime time = LocalDateTime.parse(orderedOn, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        return String.valueOf(time.plus(10, ChronoUnit.HOURS));
    }
} 
```

Here we are assuming plan time for order delivery step should be 10 hours from the order placed time.

14. Now we are ready to test the application. Restart the application and send the `ORDER_PLACED_EVENT` to `order-event-topic` kafka topic. This topic name is defined in the `application.properties` as `spring.cloud.stream.bindings.event-processor.destination=order-event-topic`

    _You can send the request using kafka-console-producer.sh as below_
    `_./bin/kafka-console-producer.sh --bootstrap-server=localhost:9092 --topic order-event-topic < /mnt/c/source/order-process-monitor/requests/request1.json_`
15. You can check the planned measurements are computed and published to the `measurement-instance-out-0`.


