package fu.hl.g64.motorflow.stockcount.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * UC-93: counted quantities to save as a draft. {@code countedQty} null leaves the line uncounted; a line of another
 * count or a quantity below 0 is SC-8 (checked in the service, not here).
 */
public record StockCountLinesSaveRequest(
        @NotNull(message = "Vui lòng gửi danh sách dòng.")
        List<@NotNull @Valid Line> lines) {

    public record Line(
            @NotNull(message = "Vui lòng chọn dòng.")
            Long lineId,

            Integer countedQty) {
    }
}
