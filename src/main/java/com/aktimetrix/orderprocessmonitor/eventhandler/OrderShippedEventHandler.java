package com.aktimetrix.orderprocessmonitor.eventhandler;

import com.aktimetrix.core.event.handler.AbstractMilestoneEventHandler;
import com.aktimetrix.core.stereotypes.EventHandler;
import org.springframework.stereotype.Component;

@Component
@EventHandler(eventType = "ORDER_SHIPPED_EVENT")
public class OrderShippedEventHandler extends AbstractMilestoneEventHandler {
}
