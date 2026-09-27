package com.aktimetrix.orderprocessmonitor.eventhandler;

import com.aktimetrix.core.event.handler.AbstractMilestoneEventHandler;
import com.aktimetrix.core.stereotypes.EventHandler;
import org.springframework.stereotype.Component;

@Component
@EventHandler(eventType = "ORDER_DELIVERED_EVENT")
public class OrderDeliveredEventHandler extends AbstractMilestoneEventHandler {
}
