package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-106 for a transfer order: the goods arrived at the destination, recorded with an optional time and optional photos
 * of the shipment. {@code arrivedAt} is now when left out.
 */
public record TransferReceiptRequest(
        LocalDateTime arrivedAt,

        @Size(max = 10, message = "Tối đa 10 tệp.")
        List<@NotNull @Valid AttachmentUpload> photos) {
}