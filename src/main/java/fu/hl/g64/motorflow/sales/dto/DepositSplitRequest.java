package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record DepositSplitRequest(
        @PositiveOrZero @Max(100_000_000_000L) Long cashAmount,
        @PositiveOrZero @Max(100_000_000_000L) Long transferAmount,
        @Size(max = 100) String transactionReference,
        LocalDate depositExpiresOn,
        @NotNull @PositiveOrZero Long expectedDepositRecorded,
        @Size(max = 10) List<@NotNull @Valid AttachmentUpload> evidence) {
}
