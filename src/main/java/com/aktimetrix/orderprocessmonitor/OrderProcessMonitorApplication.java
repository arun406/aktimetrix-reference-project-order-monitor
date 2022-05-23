package com.aktimetrix.orderprocessmonitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@ComponentScan(basePackages = {"com.aktimetrix.orderprocessmonitor", "com.aktimetrix.core"})
@SpringBootApplication
public class OrderProcessMonitorApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderProcessMonitorApplication.class, args);
    }
}
