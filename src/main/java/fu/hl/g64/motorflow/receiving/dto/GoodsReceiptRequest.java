package fu.hl.g64.motorflow.receiving.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-106: a supplier delivery that arrived with its delivery document. {@code arrivedAt} is now when left out.
 * {@code replacementForCaseIds} makes it a replacement delivery for those discrepancy cases of the PO (UC-106 Step
 * 1_Supplier replacement); without it the delivery brings the PO's outstanding lines. {@code documents} are the
 * photos of the delivery document (UC-106 step 3: at least one), already uploaded with an ATTACHMENT signature.
 */
public record GoodsReceiptRequest(
        @NotBlank(message = "Vui lòng nhập số chứng từ giao hàng.")
        @Size(max = 50, message = "Số chứng từ tối đa 50 ký tự.")
        String deliveryDocumentNo,

        @NotNull(message = "Vui lòng nhập ngày chứng từ.")
        LocalDate deliveryDocumentDate,

        LocalDateTime arrivedAt,

        @Size(max = 100, message = "Tên người giao tối đa 100 ký tự.")
        String delivererName,

        @Size(max = 20, message = "Biển số xe tối đa 20 ký tự.")
        String vehiclePlateNo,

        @Size(max = 100, message = "Tối đa 100 hồ sơ sai lệch.")
        List<@NotNull(message = "Vui lòng chọn hồ sơ sai lệch.") Long> replacementForCaseIds,

        @NotEmpty(message = "Vui lòng đính kèm ảnh chứng từ giao hàng.")
        @Size(max = 10, message = "Tối đa 10 tệp.")
        List<@NotNull @Valid AttachmentUpload> documents) {
}
