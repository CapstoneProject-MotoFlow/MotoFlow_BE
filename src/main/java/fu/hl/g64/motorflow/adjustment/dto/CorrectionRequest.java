package fu.hl.g64.motorflow.adjustment.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * UC-81 step 3: the corrected values of a posted document, with the reason and evidence (plan 6.3).
 * <ul>
 *     <li>SALES_ORDER (COMPLETED): {@code lines} with the delivered quantity of order lines;</li>
 *     <li>GOODS_RECEIPT_NOTE (CONFIRMED supplier delivery): {@code lines} with the quantity put into stock;</li>
 *     <li>DEBT_PAYMENT (VERIFIED): {@code correctedAmount};</li>
 *     <li>OPENING_RECEIVABLE (opening debt): {@code correctedAmount}.</li>
 * </ul>
 * Lines left out keep their values.
 */
public record CorrectionRequest(
        @NotNull(message = "Vui lòng chọn loại chứng từ.")
        CorrectedDocument documentType,

        @NotNull(message = "Vui lòng chọn chứng từ.")
        Long documentId,

        @Size(max = 100, message = "Tối đa 100 dòng.")
        List<@Valid @NotNull(message = "Dòng không được để trống.") Line> lines,

        @PositiveOrZero(message = "Số tiền không được âm.")
        Long correctedAmount,

        @NotBlank(message = "Vui lòng nhập lý do sửa.")
        @Size(max = 500, message = "Lý do sửa tối đa 500 ký tự.")
        String reason,

        @Size(max = 10, message = "Tối đa 10 tệp bằng chứng.")
        List<@Valid @NotNull(message = "Tệp bằng chứng không được để trống.") AttachmentUpload> attachments) {

    /** {@code lineId}: the id of the sales order line or GRN line. */
    public record Line(
            @NotNull(message = "Vui lòng chọn dòng.")
            Long lineId,

            @NotNull(message = "Vui lòng nhập số lượng đúng.")
            @PositiveOrZero(message = "Số lượng không được âm.")
            Integer correctedQty) {
    }

    public enum CorrectedDocument { SALES_ORDER, GOODS_RECEIPT_NOTE, DEBT_PAYMENT, OPENING_RECEIVABLE }
}
