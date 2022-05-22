package com.aktimetrix.orderprocessmonitor.processhandler;

import com.aktimetrix.core.api.Constants;
import com.aktimetrix.core.api.Context;
import com.aktimetrix.core.impl.AbstractProcessor;
import com.aktimetrix.core.stereotypes.ProcessHandler;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ProcessHandler(processType = "ORDER_DELIVERY")
public class OrderProcessor extends AbstractProcessor {
    @Override
    protected Map<String, Object> getStepMetadata(Context context) {
        HashMap<String, Object> map = new HashMap<>();
        LinkedHashMap<String, Object> entity = (LinkedHashMap<String, Object>) context.getProperty(Constants.ENTITY);

        String orderedOn = (String) entity.get("orderedOn");
        LocalDateTime time = LocalDateTime.parse(orderedOn, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        map.put("orderedOn", time);
        map.put("orderedId", (String) entity.get("orderId"));
        return map;
    }

    @Override
    protected Map<String, Object> getProcessMetadata(Context context) {
        return (LinkedHashMap) context.getProperty(Constants.ENTITY);
    }
}
