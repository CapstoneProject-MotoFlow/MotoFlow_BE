package fu.hl.g64.motorflow.discrepancy.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * UC-46 Step 1_Actual supplier return: goods handed back to the supplier, with the handover evidence already uploaded
 * with an ATTACHMENT signature.
 */
public record SupplierReturnRequest(
        @NotNull(message = "Vui lòng nhập số lượng trả nhà cung cấp.")
        @Positive(message = "Số lượng phải lớn hơn 0.")
        @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000.")
        Integer quantity,

        @Size(max = 255, message = "Ghi chú tối đa 255 ký tự.")
        String note,

        @Size(max = 10, message = "Tối đa 10 tệp.")
        List<@NotNull @Valid AttachmentUpload> evidence) {
}
