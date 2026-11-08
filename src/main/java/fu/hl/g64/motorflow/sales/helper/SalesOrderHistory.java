package fu.hl.g64.motorflow.sales.helper;

import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.SalesOrderLine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The history of a sales order lives in audit_log (plan 6.5): version 1 in {@code SALES_ORDER_CREATED}, each later
 * version in one {@code SALES_ORDER_VERSIONED} row with the version before ({@code old_data}) and after
 * ({@code new_data}), and the picking and confirmation steps in their own rows. The version number of an order is
 * 1 plus its VERSIONED rows (BR-36).
 */
public final class SalesOrderHistory {

    public static final String ENTITY = "SALES_ORDER";
    public static final String CREATED = "SALES_ORDER_CREATED";
    public static final String VERSIONED = "SALES_ORDER_VERSIONED";
    public static final String PICKED = "SALES_ORDER_PICKED";
    public static final String CONFIRMED = "SALES_ORDER_CONFIRMED";
    /** A payment or refund that did not count; the payment row keeps no record of who rejected it. */
    public static final String PAYMENT_REJECTED = "SALES_ORDER_PAYMENT_REJECTED";
    public static final String COMPLETED = "SALES_ORDER_COMPLETED";
    public static final String CANCELLED = "SALES_ORDER_CANCELLED";
    public static final List<String> ACTIONS = List.of(CREATED, VERSIONED, PICKED, CONFIRMED, PAYMENT_REJECTED,
            COMPLETED, CANCELLED);
    public static final List<String> VERSION_ACTIONS = List.of(CREATED, VERSIONED);

    /** Why a new version was saved: the seller changed the order (UC-55), or delivered less than requested (UC-58). */
    public static final String CAUSE_UPDATE = "UPDATE";
    public static final String CAUSE_DELIVERY = "DELIVERY";

    private SalesOrderHistory() {
    }

    /** What one version of the order holds, as stored in audit_log. */
    public static Map<String, Object> snapshot(int version, String cause, SalesOrder order, List<SalesOrderLine> lines) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", version);
        data.put("cause", cause);
        data.put("customer_id", order.getCustomerId());
        data.put("customer_name", order.getCustomerNameSnapshot());
        data.put("customer_phone", order.getCustomerPhoneSnapshot());
        data.put("price_type", order.getPriceType().name());
        data.put("discount_rate", order.getDiscountRate().toPlainString());
        data.put("lines", lines.stream().map(line -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("product_id", line.getProductId());
            row.put("requested_qty", line.getRequestedQty());
            row.put("delivered_qty", line.getDeliveredQty());
            row.put("reference_price", line.getReferencePrice());
            row.put("actual_unit_price", line.getActualUnitPrice());
            return row;
        }).toList());
        return data;
    }

    /** The version a CREATED or VERSIONED row saved. */
    public static int version(AuditLog step) {
        return number(step.getNewData().get("version")).intValue();
    }

    /** [product id → requested quantity] of the version a CREATED or VERSIONED row saved. */
    public static Map<Long, Integer> requested(Map<String, Object> snapshot) {
        Map<Long, Integer> lines = new LinkedHashMap<>();
        for (Map<?, ?> line : lines(snapshot)) {
            lines.put(number(line.get("product_id")).longValue(), number(line.get("requested_qty")).intValue());
        }
        return lines;
    }

    /** The lines of a snapshot in the order they were saved. */
    public static List<Map<?, ?>> lines(Map<String, Object> snapshot) {
        if (snapshot == null || !(snapshot.get("lines") instanceof List<?> rows)) {
            return List.of();
        }
        return rows.stream().<Map<?, ?>>map(row -> (Map<?, ?>) row).toList();
    }

    /** True if the order's current lines ask for the same parts and quantities as the version saved in the row. */
    public static boolean samePicking(Map<String, Object> snapshot, List<SalesOrderLine> current) {
        Map<Long, Integer> now = new LinkedHashMap<>();
        current.forEach(line -> now.put(line.getProductId(), line.getRequestedQty()));
        return Objects.equals(requested(snapshot), now);
    }

    public static Number number(Object value) {
        return value instanceof Number n ? n : Long.valueOf(value.toString());
    }
}
