package com.aktimetrix.orderprocessmonitor;

/**
 * The vocabulary of the order delivery process, as used in {@code aktimetrix/process-definitions.json} and
 * {@code aktimetrix/step-definitions.json}.
 */
public final class OrderDelivery {

    public static final String PROCESS = "ORDER_DELIVERY";

    public static final String PLACE = "PLACE";
    public static final String SHIP = "SHIP";
    public static final String DELIVER = "DELIVER";

    public static final String ORDER_PLACED_EVENT = "ORDER_PLACED_EVENT";
    public static final String ORDER_SHIPPED_EVENT = "ORDER_SHIPPED_EVENT";
    public static final String ORDER_DELIVERED_EVENT = "ORDER_DELIVERED_EVENT";

    /**
     * The planned and actual time of a step.
     */
    public static final String TIME = "TIME";

    private OrderDelivery() {
    }
}
