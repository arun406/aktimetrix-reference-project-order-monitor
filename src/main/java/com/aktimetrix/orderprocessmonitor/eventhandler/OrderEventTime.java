package com.aktimetrix.orderprocessmonitor.eventhandler;

import com.aktimetrix.core.transferobjects.Event;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Reads when something happened to an order from the order itself, e.g. {@code "shippedAt": "2022-05-23 01:30:00"}.
 */
final class OrderEventTime {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private OrderEventTime() {
    }

    /**
     * @return the time in the entity field, or {@code null} when the event does not carry it
     */
    static LocalDateTime read(Event<?, ?> event, String field) {
        if (!(event.getEntity() instanceof Map)) {
            return null;
        }
        Object value = ((Map<?, ?>) event.getEntity()).get(field);
        return value == null ? null : LocalDateTime.parse(value.toString(), FORMAT);
    }
}
