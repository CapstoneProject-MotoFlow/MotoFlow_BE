package fu.hl.g64.motorflow.document.dto;

import java.time.LocalDate;

/**
 * Filters of UC-78, all optional. {@code q}: the document number ("#15" or "15") or part of the customer or supplier
 * name (screen 11.1.1); {@code number}: the document number only, kept for older callers; {@code from} and
 * {@code to}: the document date, both days included; {@code storeId}: Owner only, a Branch Manager always sees their
 * own store.
 */
public record DocumentSearch(DocumentType type, String q, String number, LocalDate from, LocalDate to, Long storeId) {
}
