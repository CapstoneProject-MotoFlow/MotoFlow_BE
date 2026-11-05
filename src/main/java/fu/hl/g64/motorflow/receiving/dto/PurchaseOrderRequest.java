package fu.hl.g64.motorflow.receiving.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * UC-40: a PO for one active supplier and one active receiving warehouse, with the parts, quantities and purchase
 * prices agreed outside MotoFlow (BR-19, BR-20). The expected arrival and the note are the delivery details.
 */
public record PurchaseOrderRequest(
        @NotNull(message = "Vui lòng chọn nhà cung cấp.")
        Long supplierId,

        @NotNull(message = "Vui lòng chọn kho nhận.")
        Long storeId,

        LocalDate expectedArrival,

        @Size(max = 500, message = "Ghi chú tối đa 500 ký tự.")
        String note,

        @NotEmpty(message = "Vui lòng thêm ít nhất một phụ tùng.")
        @Size(max = 500, message = "Mỗi yêu cầu nhập hàng có tối đa 500 dòng.")
        List<@NotNull @Valid PurchaseOrderLineRequest> lines) {
}
