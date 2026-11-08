package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;

import java.time.LocalDate;

/**
 * UC-52 filters, all optional (screen 8.1.1). {@code q} matches the order number (with or without "#"), the customer
 * name or phone. A Branch Manager sees their own store, Owner every store or the one chosen, Staff the orders they are
 * picking. {@code view} defaults to OPEN, which shows every unfinished order whatever its day; the other views keep
 * the creation days chosen, today when none is.
 * <p>
 * {@code status} keeps one status inside the view, and on its own searches every view: an unfinished status
 * (waiting for picking, picked, confirmed) shows every day like OPEN, a finished one keeps the creation days chosen,
 * today when none is. A status outside the chosen view finds nothing.
 */
public record SalesOrderSearch(Long storeId, SalesOrderView view, SalesOrderStatus status, String q,
                               LocalDate createdFrom, LocalDate createdTo) {
}
