package fu.hl.g64.motorflow.adjustment.dto;

import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * UC-83 step 3: lost, damaged or disposed goods to write off, with evidence.
 *
 * @param storeId            the warehouse; a Branch Manager may leave it out for their own store, Owner must give it
 * @param transferOrderId    UC-83 Step 1_Transit loss: the transfer whose goods were lost on the way; every line is
 *                           then IN_TRANSIT at the destination warehouse, without a lot
 * @param goodsReceiptNoteId held goods of a supplier delivery (wrong, damaged or rejected) that cannot go back to the
 *                           supplier (UC-46 Step 3_Goods cannot be returned)
 */
public record StockLossRequest(
        Long storeId,
        Long transferOrderId,
        Long goodsReceiptNoteId,

        @NotBlank(message = "Vui lòng nhập mô tả sự việc.")
        @Size(max = 500, message = "Mô tả sự việc tối đa 500 ký tự.")
        String reason,

        @NotEmpty(message = "Vui lòng nhập ít nhất một dòng hàng.")
        @Size(max = 100, message = "Tối đa 100 dòng hàng.")
        List<@Valid @NotNull(message = "Dòng hàng không được để trống.") Line> lines,

        @Size(max = 10, message = "Tối đa 10 tệp bằng chứng.")
        List<@Valid @NotNull(message = "Tệp bằng chứng không được để trống.") AttachmentUpload> attachments) {

    /**
     * @param lotId null: lots are taken FEFO, then FIFO, when the request is applied; then it must be the only line of
     *              the part and status
     */
    public record Line(
            @NotNull(message = "Vui lòng chọn phụ tùng.")
            Long productId,

            Long lotId,

            @NotNull(message = "Vui lòng chọn trạng thái tồn.")
            StockStatus stockStatus,

            @NotNull(message = "Vui lòng nhập số lượng.")
            @Positive(message = "Số lượng phải lớn hơn 0.")
            Integer quantity) {
    }
}
