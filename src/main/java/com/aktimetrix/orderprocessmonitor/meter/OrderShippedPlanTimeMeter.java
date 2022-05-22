package com.aktimetrix.orderprocessmonitor.meter;

import com.aktimetrix.core.meter.impl.AbstractMeter;
import com.aktimetrix.core.model.StepInstance;
import com.aktimetrix.core.stereotypes.Measurement;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Component
@Measurement(code = "TIME", stepCode = "SHIP")
public class OrderShippedPlanTimeMeter extends AbstractMeter {
    @Override
    protected String getMeasurementUnit(String tenant, StepInstance step) {
        return "TIMESTAMP";
    }

    @Override
    protected String getMeasurementValue(String tenant, StepInstance step) {
        String orderedOn = (String) step.getMetadata().get("orderedOn");
        LocalDateTime time = LocalDateTime.parse(orderedOn, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

        return String.valueOf(time.plus(2, ChronoUnit.HOURS));
    }
}
