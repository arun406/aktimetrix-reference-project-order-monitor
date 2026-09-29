package com.aktimetrix.orderprocessmonitor;

/**
 * The vocabulary of the order delivery process, as used in {@code aktimetrix/process-definitions.json} and
 * {@code aktimetrix/step-definitions.json}.
 */
public final class OrderDelivery {

    public static final String PROCESS = "ORDER_DELIVERY";

    public static final String CONFIRM = "CONFIRM";
    public static final String PAY = "PAY";
    public static final String HANDOVER = "HANDOVER";
    public static final String ACCEPT = "ACCEPT";
    public static final String TRAVEL = "TRAVEL";
    public static final String DELIVERED = "DELIVERED";
    public static final String RATED = "RATED";

    /**
     * The planned and actual time of a step, and the planned completion of the whole order.
     */
    public static final String TIME = "TIME";

    private OrderDelivery() {
    }
}
