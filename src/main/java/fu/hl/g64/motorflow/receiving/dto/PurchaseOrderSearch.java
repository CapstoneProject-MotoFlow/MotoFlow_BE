package fu.hl.g64.motorflow.receiving.dto;

import java.time.LocalDate;

/**
 * UC-39 filters, all optional (screen 6.1.1). {@code q} matches the PO number (with or without "#") or the supplier
 * name or code. A Branch Manager always sees their own warehouse. {@code view} defaults to OPEN. A creation range
 * applies to every view; the views other than OPEN keep the 30 days up to today when none is chosen.
 */
public record PurchaseOrderSearch(String q, Long storeId, Long supplierId, PurchaseOrderView view,
                                  LocalDate createdFrom, LocalDate createdTo) {
}
