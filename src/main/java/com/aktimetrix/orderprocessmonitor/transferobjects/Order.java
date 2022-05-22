package com.aktimetrix.orderprocessmonitor.transferobjects;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class Order implements Serializable {
    String orderId;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    LocalDateTime orderedOn;
    String customerId;
    double orderTotal;
    String orderCurrency;
    String productId;
    int quantity;
    String shippingAddress;
}
