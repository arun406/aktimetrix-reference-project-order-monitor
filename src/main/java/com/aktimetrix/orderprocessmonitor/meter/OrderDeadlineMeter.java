package com.aktimetrix.orderprocessmonitor.meter;

import com.aktimetrix.core.meter.impl.AbstractProcessMeter;
import com.aktimetrix.core.model.ProcessInstance;
import com.aktimetrix.core.stereotypes.Measurement;
import com.aktimetrix.orderprocessmonitor.OrderDelivery;
import org.springframework.stereotype.Component;

/**
 * The promise for the order as a whole: a priority customer's order is delivered within 1 day, others within 3.
 * A planned TIME of the process is its deadline.
 */
@Component
@Measurement(code = OrderDelivery.TIME, processCode = OrderDelivery.PROCESS)
public class OrderDeadlineMeter extends AbstractProcessMeter {

    @Override
    protected String getMeasurementUnit(String tenant, ProcessInstance process) {
        return "TIMESTAMP";
    }

    @Override
    protected String getMeasurementValue(String tenant, ProcessInstance process) {
        boolean priority = Boolean.TRUE.equals(process.getMetadata().get("priority"));
        return String.valueOf(metadataTime(process, "createdAt").plusDays(priority ? 1 : 3));
    }
}
