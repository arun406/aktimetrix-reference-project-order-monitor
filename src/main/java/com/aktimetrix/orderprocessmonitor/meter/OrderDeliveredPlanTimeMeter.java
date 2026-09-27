package com.aktimetrix.orderprocessmonitor.meter;

import com.aktimetrix.core.meter.impl.AbstractMeter;
import com.aktimetrix.core.model.StepInstance;
import com.aktimetrix.core.stereotypes.Measurement;
import com.aktimetrix.orderprocessmonitor.OrderDelivery;
import org.springframework.stereotype.Component;

/**
 * Plans the DELIVER step: an order should be delivered within 10 hours of being placed.
 */
@Component
@Measurement(code = OrderDelivery.TIME, stepCode = OrderDelivery.DELIVER)
public class OrderDeliveredPlanTimeMeter extends AbstractMeter {

    static final int HOURS_AFTER_ORDER = 10;

    @Override
    protected String getMeasurementUnit(String tenant, StepInstance step) {
        return "TIMESTAMP";
    }

    @Override
    protected String getMeasurementValue(String tenant, StepInstance step) {
        return String.valueOf(metadataTime(step, "orderedOn").plusHours(HOURS_AFTER_ORDER));
    }
}
