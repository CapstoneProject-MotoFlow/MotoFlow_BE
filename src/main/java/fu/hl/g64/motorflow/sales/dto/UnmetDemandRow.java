package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;

import java.time.LocalDateTime;

/**
 * UC-59 step 2: one part a customer asked for and did not receive. {@code occurredAt} is when the order was completed
 * or cancelled; a cancelled order delivered nothing, so its {@code deliveredQty} is 0. {@code sellableQty} is what the
 * order's store can hand over now (available, not expired), for selling the missing quantity on a new order.
 */
public record UnmetDemandRow(LocalDateTime occurredAt, long orderId, SalesOrderStatus orderStatus, long storeId,
                             String storeName, Long customerId, String customerName, String customerPhone,
                             long productId, String sku, String partName, String baseUnit, int requestedQty,
                             int deliveredQty, int missingQty, int sellableQty) {
}
