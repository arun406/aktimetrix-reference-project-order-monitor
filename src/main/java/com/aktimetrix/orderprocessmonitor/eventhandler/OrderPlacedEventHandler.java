package com.aktimetrix.orderprocessmonitor.eventhandler;

import com.aktimetrix.core.event.handler.AbstractEventHandler;
import com.aktimetrix.core.stereotypes.EventHandler;
import com.aktimetrix.core.transferobjects.Event;
import com.aktimetrix.orderprocessmonitor.OrderDelivery;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Optional: starts the ORDER_DELIVERY process like the default handler would, but takes the actual time of the
 * PLACE step from the order's {@code orderedOn} rather than from the event envelope.
 */
@Component
@EventHandler(eventType = OrderDelivery.ORDER_PLACED_EVENT)
public class OrderPlacedEventHandler extends AbstractEventHandler {

    @Override
    protected LocalDateTime occurredAt(Event<?, ?> event) {
        return Objects.requireNonNullElseGet(OrderEventTime.read(event, "orderedOn"), () -> super.occurredAt(event));
    }
}
