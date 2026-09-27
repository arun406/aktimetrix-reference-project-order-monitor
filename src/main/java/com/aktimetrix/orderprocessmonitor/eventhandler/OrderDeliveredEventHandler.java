package com.aktimetrix.orderprocessmonitor.eventhandler;

import com.aktimetrix.core.event.handler.AbstractMilestoneEventHandler;
import com.aktimetrix.core.stereotypes.EventHandler;
import com.aktimetrix.core.transferobjects.Event;
import com.aktimetrix.orderprocessmonitor.OrderDelivery;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Optional: completes the DELIVER step like the default handler would, but takes its actual time from the order's
 * {@code deliveredAt} rather than from the event envelope.
 */
@Component
@EventHandler(eventType = OrderDelivery.ORDER_DELIVERED_EVENT)
public class OrderDeliveredEventHandler extends AbstractMilestoneEventHandler {

    @Override
    protected LocalDateTime occurredAt(Event<?, ?> event) {
        return Objects.requireNonNullElseGet(OrderEventTime.read(event, "deliveredAt"), () -> super.occurredAt(event));
    }
}
