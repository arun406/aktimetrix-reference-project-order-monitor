package com.aktimetrix.orderprocessmonitor.definitions;

import com.aktimetrix.core.definitions.Definitions;
import com.aktimetrix.core.model.ProcessInstance;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The definitions in Java and in YAML are the same, so either can serve as the example.
 */
class OrderDeliveryDefinitionsTest {

    private final Definitions java = new OrderDeliveryDefinitions().orderDelivery();

    @Test
    void theYamlExampleDescribesTheSameProcessAsTheJavaDefinitions() throws Exception {
        final Definitions yaml;
        try (InputStream in = getClass().getResourceAsStream("/examples/order-delivery.yaml")) {
            yaml = new ObjectMapper(new YAMLFactory()).readValue(in, Definitions.class);
        }
        final ObjectMapper json = new ObjectMapper();

        assertThat(json.writeValueAsString(java.processDefinitions()))
                .isEqualTo(json.writeValueAsString(yaml.processDefinitions()));
        assertThat(yaml.stepDefinitions()).isEmpty();
    }

    @Test
    void thePlanningRulesAreTheOrdersDeadlineAndTheDeliveryTime() {
        assertThat(java.getRules()).extracting(r -> r.getProcessCode() + "/" + r.getStepCode() + "/" + r.getMeasurementCode())
                .containsExactly("ORDER_DELIVERY/null/TIME", "ORDER_DELIVERY/DELIVERED/TIME");
        assertThat(java.getRules()).extracting(Definitions.Rule::getTenant).containsOnly("AA");

        final ProcessInstance priority = new ProcessInstance();
        priority.setMetadata(Map.of("priority", true, "createdAt", "2024-03-01 09:00:00"));
        final ProcessInstance standard = new ProcessInstance();
        standard.setMetadata(Map.of("priority", false, "createdAt", "2024-03-01 09:00:00"));
        final Definitions.Rule deadline = java.getRules().get(0);

        assertThat(deadline.getProcessRule().apply(priority)).isEqualTo(LocalDateTime.of(2024, 3, 2, 9, 0));
        assertThat(deadline.getProcessRule().apply(standard)).isEqualTo(LocalDateTime.of(2024, 3, 4, 9, 0));
    }
}
