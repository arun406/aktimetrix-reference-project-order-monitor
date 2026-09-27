package com.aktimetrix.orderprocessmonitor.eventhandler;

import com.aktimetrix.core.event.handler.AbstractMilestoneEventHandler;
import com.aktimetrix.core.stereotypes.EventHandler;
import com.aktimetrix.core.transferobjects.Event;
import com.aktimetrix.orderprocessmonitor.OrderDelivery;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Optional: completes the SHIP step like the default handler would, but takes its actual time from the order's
 * {@code shippedAt} rather than from the event envelope.
 */
@Component
@EventHandler(eventType = OrderDelivery.ORDER_SHIPPED_EVENT)
public class OrderShippedEventHandler extends AbstractMilestoneEventHandler {

    @Override
    protected LocalDateTime occurredAt(Event<?, ?> event) {
        return Objects.requireNonNullElseGet(OrderEventTime.read(event, "shippedAt"), () -> super.occurredAt(event));
    }
}
