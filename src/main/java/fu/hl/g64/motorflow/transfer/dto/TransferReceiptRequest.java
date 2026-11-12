package fu.hl.g64.motorflow.transfer.dto;

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
 * UC-106 for a transfer order: the goods arrived at the destination with the transfer's delivery document, recorded
 * with the same fields and rules as a supplier delivery. {@code arrivedAt} is now when left out; {@code documents} are
 * the photos of the delivery document (at least one), already uploaded with an ATTACHMENT signature.
 */
public record TransferReceiptRequest(
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

        @NotEmpty(message = "Vui lòng đính kèm ảnh chứng từ giao hàng.")
        @Size(max = 10, message = "Tối đa 10 tệp.")
        List<@NotNull @Valid AttachmentUpload> documents) {
}
