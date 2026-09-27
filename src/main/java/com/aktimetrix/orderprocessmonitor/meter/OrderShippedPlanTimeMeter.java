package com.aktimetrix.orderprocessmonitor.meter;

import com.aktimetrix.core.meter.impl.AbstractMeter;
import com.aktimetrix.core.model.StepInstance;
import com.aktimetrix.core.stereotypes.Measurement;
import com.aktimetrix.orderprocessmonitor.OrderDelivery;
import org.springframework.stereotype.Component;

/**
 * Plans the SHIP step: an order should ship within 2 hours of being placed.
 */
@Component
@Measurement(code = OrderDelivery.TIME, stepCode = OrderDelivery.SHIP)
public class OrderShippedPlanTimeMeter extends AbstractMeter {

    static final int HOURS_AFTER_ORDER = 2;

    @Override
    protected String getMeasurementUnit(String tenant, StepInstance step) {
        return "TIMESTAMP";
    }

    @Override
    protected String getMeasurementValue(String tenant, StepInstance step) {
        return String.valueOf(metadataTime(step, "orderedOn").plusHours(HOURS_AFTER_ORDER));
    }
}
