package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.sales.entity.enums.WaitingOrderState;

import java.time.LocalDate;
import java.util.Set;

/**
 * UC-59 filters, all optional. {@code states} default to WAITING and READY (GVHD 24/11). The days are when the order
 * was taken (cancelled, for a cancelled order); with no days given, waiting orders are all listed and released or
 * cancelled ones over the last 30 days. {@code q} matches the SKU, part name, customer name or phone. A Branch Manager
 * sees their own store only. Review 25/11 (R-08): {@code orderId} lists the lines of one order, so the
 * "Soạn phần có hàng" suggestion covers every line of the order, not only those on the page shown.
 */
public record UnmetDemandSearch(Long storeId, Long productId, Long customerId, String q, LocalDate from,
                                LocalDate to, Set<WaitingOrderState> states, Long orderId) {
}
