package fu.hl.g64.motorflow.document.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One document found by UC-78. {@code number} is "#" + id (BR-01). {@code subType}: completion type of a sales order
 * (PAID, CREDIT, null when cancelled), receipt type of a goods receipt, payment type of a payment, adjustment type of
 * an adjustment request. {@code documentDate}: when it took effect (completed or cancelled, confirmed, dispatched,
 * verified, submitted). {@code storeId}: the store the document belongs to (the receiving store of a goods receipt,
 * the source of a transfer order); {@code otherStoreId}: the other store of a transfer (its destination, or the store a
 * transferred receipt came from). {@code partnerName}: customer or supplier. {@code value}: total of a sales order,
 * amount of a payment, value accepted of a supplier receipt for Owner only (BR-72), otherwise null.
 * {@code printForms}: what UC-80 can print of it.
 */
public record DocumentRow(DocumentType type, long id, String number, String subType, String status,
                          LocalDateTime documentDate, long storeId, String storeName, Long otherStoreId,
                          String otherStoreName, String partnerName, Long value, List<PrintForm> printForms) {
}
