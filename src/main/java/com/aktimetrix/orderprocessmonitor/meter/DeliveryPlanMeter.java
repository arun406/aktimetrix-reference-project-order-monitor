package com.aktimetrix.orderprocessmonitor.meter;

import com.aktimetrix.core.meter.impl.AbstractMeter;
import com.aktimetrix.core.model.StepInstance;
import com.aktimetrix.core.stereotypes.Measurement;
import com.aktimetrix.orderprocessmonitor.OrderDelivery;
import org.springframework.stereotype.Component;

/**
 * When the parcel should reach the customer: 3 h 15 min after the order is created for a priority customer, 2 days
 * for others.
 */
@Component
@Measurement(code = OrderDelivery.TIME, stepCode = OrderDelivery.DELIVERED)
public class DeliveryPlanMeter extends AbstractMeter {

    @Override
    protected String getMeasurementUnit(String tenant, StepInstance step) {
        return "TIMESTAMP";
    }

    @Override
    protected String getMeasurementValue(String tenant, StepInstance step) {
        boolean priority = Boolean.TRUE.equals(step.getMetadata().get("priority"));
        return String.valueOf(priority
                ? metadataTime(step, "createdAt").plusHours(3).plusMinutes(15)
                : metadataTime(step, "createdAt").plusDays(2));
    }
}
