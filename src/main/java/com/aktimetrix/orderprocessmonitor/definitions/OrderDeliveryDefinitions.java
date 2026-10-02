package com.aktimetrix.orderprocessmonitor.definitions;

import com.aktimetrix.core.definitions.Definitions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

import static com.aktimetrix.core.definitions.Planning.metadataTime;
import static com.aktimetrix.orderprocessmonitor.OrderDelivery.ACCEPT;
import static com.aktimetrix.orderprocessmonitor.OrderDelivery.CONFIRM;
import static com.aktimetrix.orderprocessmonitor.OrderDelivery.DELIVERED;
import static com.aktimetrix.orderprocessmonitor.OrderDelivery.HANDOVER;
import static com.aktimetrix.orderprocessmonitor.OrderDelivery.PAY;
import static com.aktimetrix.orderprocessmonitor.OrderDelivery.PROCESS;
import static com.aktimetrix.orderprocessmonitor.OrderDelivery.RATED;
import static com.aktimetrix.orderprocessmonitor.OrderDelivery.TRAVEL;

/**
 * The order delivery process, declared with the Aktimetrix Java DSL: the events that start and cancel it, its seven
 * steps with their plans and tolerances, the measurements compared with their plans, and the two planning rules.
 * <p>
 * Every step is planned from the moment the order is created. Two plans follow a rule rather than a duration:
 * <ul>
 *     <li>the order as a whole: a priority customer's order completes within 1 day, others within 3;</li>
 *     <li>{@code DELIVERED}: a priority customer's parcel arrives within 3 h 15 min, others within 2 days.</li>
 * </ul>
 * The same definitions, as YAML, are in {@code src/main/resources/examples/order-delivery.yaml}.
 */
@Configuration
public class OrderDeliveryDefinitions {

    @Bean
    Definitions orderDelivery() {
        return Definitions.tenant("AA")
                .process(PROCESS, order -> order
                        .name("Order delivery")
                        .description("From the moment an order is created until it is delivered, and rated")
                        .entityType("com.ecom.order")
                        .startsOn("ORDER_CREATED_EVENT")
                        .cancelledOn("ORDER_CANCELLED_EVENT")
                        // the promise for the whole order: its deadline
                        .planTime(o -> metadataTime(o, "createdAt").plus(Duration.ofDays(priority(o.getMetadata()) ? 1 : 3)))
                        .measure("COST", "deliveryCost", cost -> cost
                                .value(8).unit("EUR").tolerance("10%").worseWhenHigher())
                        .metric("FUEL_PER_KM", "FUEL / DISTANCE", fuel -> fuel
                                .unit("L/KM").tolerance("10%").worseWhenHigher())

                        .step(CONFIRM, step -> step.name("Order confirmed")
                                .on("ORDER_CONFIRMED_EVENT").within("PT5M"))
                        .step(PAY, step -> step.name("Payment confirmed")
                                .on("PAYMENT_CONFIRMED_EVENT").within("PT15M").tolerance("PT5M"))
                        .step(HANDOVER, step -> step.name("Handed to the delivery agent")
                                .on("HANDED_TO_AGENT_EVENT").within("PT2H"))
                        .step(ACCEPT, step -> step.name("Delivery agent accepted")
                                .on("AGENT_ACCEPTED_EVENT").within("PT2H15M"))
                        .step(TRAVEL, step -> step.name("Travel to the customer")
                                .startsOn("TRAVEL_STARTED_EVENT").endsOn("ARRIVED_EVENT")
                                .progressOn("LOCATION_UPDATED_EVENT")
                                .within("PT3H")
                                .measure("DISTANCE", "route.distanceKm", km -> km
                                        .value(5).unit("KM").tolerance("20%").worseWhenHigher())
                                .measure("FUEL", "fuelLitres", litres -> litres
                                        .value(0.4).unit("L").tolerance("25%").worseWhenHigher()))
                        .step(DELIVERED, step -> step.name("Delivered")
                                .on("ORDER_DELIVERED_EVENT")
                                .planTime(s -> priority(s.getMetadata())
                                        ? metadataTime(s, "createdAt").plus(Duration.ofMinutes(195))
                                        : metadataTime(s, "createdAt").plus(Duration.ofDays(2)))
                                .measure("TEMPERATURE", "parcelTemperatureC", c -> c
                                        .value(30).unit("C").tolerance("5").worseWhenHigher()))
                        .step(RATED, step -> step.name("Rated by the customer")
                                .on("ORDER_RATED_EVENT").optional()
                                .measure("RATING", "review.stars", stars -> stars
                                        .value(5).unit("STARS").tolerance("1").worseWhenLower())))
                .build();
    }

    /**
     * Whether the order is a priority customer's, as {@code OrderProcessor} recorded in its metadata.
     */
    private static boolean priority(Map<String, Object> metadata) {
        return metadata != null && Boolean.TRUE.equals(metadata.get("priority"));
    }
}
