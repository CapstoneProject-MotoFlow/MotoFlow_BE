package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** GVHD 24/11: the destination's BM sends the reconciliation request with a note and photos, both optional. */
public record ReconciliationRequest(
        @Size(max = 255, message = "Ghi chú tối đa 255 ký tự.")
        String note,

        @Size(max = 10, message = "Tối đa 10 tệp.")
        List<@NotNull @Valid AttachmentUpload> files) {
}
