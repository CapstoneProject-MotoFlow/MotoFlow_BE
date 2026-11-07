package fu.hl.g64.motorflow.receivable.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * UC-67 step 3: a customer's debt to one store from before MotoFlow. {@code amount} is in VND; {@code dueDate} may be in
 * the past; {@code evidence} are the supporting documents, already uploaded with an ATTACHMENT signature.
 */
public record OpeningDebtRequest(
        @NotNull(message = "Vui lòng chọn cửa hàng.")
        Long storeId,

        @NotNull(message = "Vui lòng nhập số tiền nợ.")
        @Positive(message = "Số tiền nợ phải lớn hơn 0.")
        @Max(value = 100_000_000_000L, message = "Số tiền nợ tối đa 100.000.000.000 đ.")
        Long amount,

        @NotNull(message = "Vui lòng nhập hạn trả.")
        LocalDate dueDate,

        @NotBlank(message = "Vui lòng nhập diễn giải khoản nợ.")
        @Size(max = 500, message = "Diễn giải tối đa 500 ký tự.")
        String note,

        @NotEmpty(message = "Vui lòng đính kèm chứng từ của khoản nợ.")
        @Size(max = 10, message = "Tối đa 10 tệp.")
        List<@NotNull @Valid AttachmentUpload> evidence) {
}
