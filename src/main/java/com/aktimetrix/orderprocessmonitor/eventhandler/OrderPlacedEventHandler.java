package com.aktimetrix.orderprocessmonitor.eventhandler;

import com.aktimetrix.core.event.handler.AbstractEventHandler;
import com.aktimetrix.core.stereotypes.EventHandler;
import org.springframework.stereotype.Component;

@Component
@EventHandler(eventType = "ORDER_PLACED_EVENT")
public class OrderPlacedEventHandler extends AbstractEventHandler {
}
