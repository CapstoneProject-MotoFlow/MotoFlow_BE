package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;

import java.time.LocalDate;

/**
 * UC-97 filters; received dates are inclusive. {@code storeId} null: the Branch Manager's own warehouse, or every
 * warehouse for the Owner.
 *
 * @param q lot id (exact), or SKU, part name, manufacturer lot number or supplier name, contains, case-insensitive
 */
public record LotSearch(LotTab tab, String q, Long supplierId, LotSourceType source, LocalDate receivedFrom,
                        LocalDate receivedTo, Long storeId) {
}
