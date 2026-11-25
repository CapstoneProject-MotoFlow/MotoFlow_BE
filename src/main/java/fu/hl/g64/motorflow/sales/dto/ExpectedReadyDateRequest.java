package fu.hl.g64.motorflow.sales.dto;

import java.time.LocalDate;

/** GVHD 24/11: the day the store expects the goods of a waiting order, today or later (MSG349); null clears it. */
public record ExpectedReadyDateRequest(LocalDate expectedReadyDate) {
}
