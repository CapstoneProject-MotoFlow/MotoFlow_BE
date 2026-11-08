package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * UC-75: how much of the excess the destination received the source confirms it sent. {@code reason} is required when
 * a line confirms 0; {@code evidence} are files already uploaded for an attachment.
 */
public record ExtraDispatchRequest(
        @NotEmpty(message = "Vui lòng ghi số xuất thêm cho ít nhất một phụ tùng.")
        @Size(max = 200, message = "Tối đa 200 phụ tùng trong một phiếu chuyển kho.")
        List<@NotNull @Valid Line> lines,

        @Size(max = 500, message = "Lý do tối đa 500 ký tự.")
        String reason,

        @Size(max = 10, message = "Tối đa 10 tệp đính kèm.")
        List<@NotNull @Valid AttachmentUpload> evidence) {

    public record Line(
            @NotNull(message = "Vui lòng chọn phụ tùng.")
            Long productId,

            @NotNull(message = "Vui lòng nhập số lượng.")
            @Min(value = 0, message = "Số lượng không được âm.")
            Integer extraQty) {
    }
}
