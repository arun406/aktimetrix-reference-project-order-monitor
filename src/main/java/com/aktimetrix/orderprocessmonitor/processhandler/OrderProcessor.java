package com.aktimetrix.orderprocessmonitor.processhandler;

import com.aktimetrix.core.api.Constants;
import com.aktimetrix.core.api.Context;
import com.aktimetrix.core.impl.AbstractProcessor;
import com.aktimetrix.core.stereotypes.ProcessHandler;
import com.aktimetrix.orderprocessmonitor.OrderDelivery;
import com.aktimetrix.orderprocessmonitor.transferobjects.Order;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Creates the ORDER_DELIVERY process instance of an order, and decides what to remember about the order: the
 * process instance keeps a summary, and every step keeps what its planning rules need, the creation time and whether
 * the customer is a priority customer.
 * <p>
 * Optional: without it, Aktimetrix stores the whole order as metadata.
 */
@Component
@ProcessHandler(processType = OrderDelivery.PROCESS)
public class OrderProcessor extends AbstractProcessor {

    private final ObjectMapper objectMapper;

    public OrderProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected Map<String, Object> getProcessMetadata(Context context) {
        Order order = order(context);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("orderId", order.getOrderId());
        metadata.put("customerId", order.getCustomerId());
        metadata.put("priority", order.isPriority());
        metadata.put("createdAt", order.getCreatedAt());
        metadata.put("orderTotal", order.getOrderTotal());
        metadata.put("orderCurrency", order.getOrderCurrency());
        return metadata;
    }

    @Override
    protected Map<String, Object> getStepMetadata(Context context) {
        Order order = order(context);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("orderId", order.getOrderId());
        metadata.put("priority", order.isPriority());
        metadata.put("createdAt", order.getCreatedAt());
        return metadata;
    }

    private Order order(Context context) {
        return objectMapper.convertValue(context.getProperty(Constants.ENTITY), Order.class);
    }
}
