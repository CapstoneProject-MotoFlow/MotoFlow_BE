package fu.hl.g64.motorflow.stockcount.dto;

import fu.hl.g64.motorflow.stockcount.dto.StockCountLinesSaveRequest.Line;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** UC-93: sends the count to the Branch Manager, saving {@code lines} first as {@link StockCountLinesSaveRequest} does. */
public record StockCountSubmitRequest(List<@NotNull @Valid Line> lines) {
}
