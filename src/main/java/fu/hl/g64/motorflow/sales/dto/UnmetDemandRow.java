package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import fu.hl.g64.motorflow.sales.entity.enums.WaitingOrderState;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * UC-59 step 2 (GVHD 24/11): one line of a waiting order. {@code occurredAt} is when the order was taken, or cancelled
 * for a cancelled one. A waiting order delivered nothing yet, so {@code missingQty} is what it asks for; a released
 * order found its goods ({@code missingQty} 0). {@code readyQty} is the part of a waiting line the store's sellable
 * stock covers now once older waiting orders took theirs (BR-90), for a partial release; {@code orderReady} when
 * every line of the order is covered. {@code sellableQty} is what the store can hand over now, before that sharing
 * out. {@code overdue} when a waiting order is past its {@code expectedReadyDate} and not ready.
 */
public record UnmetDemandRow(LocalDateTime occurredAt, long orderId, SalesOrderStatus orderStatus,
                             WaitingOrderState state, long storeId, String storeName, Long customerId,
                             String customerName, String customerPhone, long lineId, long productId, String sku,
                             String partName, String baseUnit, int requestedQty, int deliveredQty, int missingQty,
                             int sellableQty, int readyQty, boolean orderReady, LocalDate expectedReadyDate,
                             boolean overdue) {
}
