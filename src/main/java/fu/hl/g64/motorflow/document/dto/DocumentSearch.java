package fu.hl.g64.motorflow.document.dto;

import java.time.LocalDate;

/**
 * Filters of UC-78, all optional. {@code number}: the document number, "#15" or "15" (BR-01); {@code from} and
 * {@code to}: the document date, both days included; {@code storeId}: Owner only, a Branch Manager always sees their
 * own store.
 */
public record DocumentSearch(DocumentType type, String number, LocalDate from, LocalDate to, Long storeId) {
}
