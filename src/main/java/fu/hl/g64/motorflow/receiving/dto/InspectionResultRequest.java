package fu.hl.g64.motorflow.receiving.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-43: the inspection result of a GRN, sent for the Branch Manager to confirm. A line of the GRN that is left out
 * did not arrive (all quantities 0). {@code arrivedAt} corrects the arrival time, or keeps it when left out.
 * {@code evidence} are photos of the goods or documents, already uploaded with an ATTACHMENT signature.
 */
public record InspectionResultRequest(
        LocalDateTime arrivedAt,

        @NotEmpty(message = "Vui lòng ghi kết quả cho ít nhất một phụ tùng.")
        @Size(max = 500, message = "Tối đa 500 dòng.")
        List<@NotNull @Valid Line> lines,

        @Size(max = 10, message = "Tối đa 10 tệp.")
        List<@NotNull @Valid AttachmentUpload> evidence) {

    /**
     * One part of the GRN: the delivery-document, delivered, wrong-item and damaged quantities (wrong item and damaged
     * are 0 when left out) and the lot data printed on the packaging. Passed and short are derived (BR-24).
     */
    public record Line(
            @NotNull(message = "Vui lòng chọn dòng của phiếu nhận hàng.")
            Long lineId,

            @NotNull(message = "Vui lòng nhập số lượng trên chứng từ.")
            @PositiveOrZero(message = "Số lượng không được âm.")
            @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000.")
            Integer documentQty,

            @NotNull(message = "Vui lòng nhập số lượng thực giao.")
            @PositiveOrZero(message = "Số lượng không được âm.")
            @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000.")
            Integer deliveredQty,

            @PositiveOrZero(message = "Số lượng không được âm.")
            @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000.")
            Integer wrongItemQty,

            @PositiveOrZero(message = "Số lượng không được âm.")
            @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000.")
            Integer damagedQty,

            @Size(max = 50, message = "Số lô tối đa 50 ký tự.")
            String manufacturerLotNo,

            LocalDate manufactureDate,

            LocalDate expiryDate) {
    }
}
