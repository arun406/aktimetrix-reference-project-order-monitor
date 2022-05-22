package com.aktimetrix.orderprocessmonitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@ComponentScan(basePackages = {"com.aktimetrix.orderprocessmonitor", "com.aktimetrix.service.processor", "com.aktimetrix.core"})
@SpringBootApplication
public class OrderProcessMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderProcessMonitorApplication.class, args);
    }
}
