package fu.hl.g64.motorflow.receiving.dto;

import java.time.LocalDate;

/**
 * UC-39 filters, all optional (screen 6.1.1). {@code q} matches the PO number (with or without "#") or the supplier
 * name or code. A Branch Manager always sees their own warehouse. {@code view} defaults to OPEN. A creation range
 * applies to every view; the views other than OPEN keep the 30 days up to today when none is chosen.
 * GVHD 20/11 (PR 6): {@code deliveredFrom}/{@code deliveredTo} filter the DISCREPANCY_OPEN tab on the day of the PO's
 * latest delivery, inclusive; that tab is sorted by that delivery, newest first.
 */
public record PurchaseOrderSearch(String q, Long storeId, Long supplierId, PurchaseOrderView view,
                                  LocalDate createdFrom, LocalDate createdTo, LocalDate deliveredFrom,
                                  LocalDate deliveredTo) {

    public PurchaseOrderSearch(String q, Long storeId, Long supplierId, PurchaseOrderView view,
                               LocalDate createdFrom, LocalDate createdTo) {
        this(q, storeId, supplierId, view, createdFrom, createdTo, null, null);
    }
}
