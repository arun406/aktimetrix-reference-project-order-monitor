package com.aktimetrix.orderprocessmonitor.transferobjects;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * The entity of an ORDER_CREATED_EVENT.
 */
@Data
public class Order implements Serializable {
    private String orderId;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    private String customerId;
    private boolean priority;
    private double orderTotal;
    private String orderCurrency;
    private String shippingAddress;
}
