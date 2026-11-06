package fu.hl.g64.motorflow.sales.dto;

import jakarta.validation.constraints.NotNull;

/**
 * UC-60 Step 2_Already paid after failed completion: complete a fully paid order. {@code version} is the version the
 * seller opened (MSG165 if another was saved since).
 */
public record CompleteSalesOrderRequest(
        @NotNull(message = "Vui lòng tải lại đơn bán.")
        Integer version) {
}
