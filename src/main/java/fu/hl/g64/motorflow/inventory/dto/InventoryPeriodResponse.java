package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.inventory.entity.enums.InventoryPeriodStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A row of the Inventory Period List (UC-99). {@code month} is yyyy-MM; {@code nextToClose} marks the oldest open
 * period, the only one the Owner may close (BR-82).
 */
public record InventoryPeriodResponse(long id, String month, LocalDate fromDate, LocalDate toDate,
                                      InventoryPeriodStatus status, Long closedBy, String closedByName,
                                      LocalDateTime closedAt, boolean nextToClose) {
}
