package fu.hl.g64.motorflow.receiving.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * UC-42: the PO as it should be from now on. Supplier and receiving warehouse stay as they are. {@code lines} is the
 * whole list: a part left out is removed from the PO, a new part is added. The reason goes into the PO history.
 */
public record PurchaseOrderUpdateRequest(
        LocalDate expectedArrival,

        @Size(max = 500, message = "Ghi chú tối đa 500 ký tự.")
        String note,

        @NotEmpty(message = "Vui lòng thêm ít nhất một phụ tùng.")
        @Size(max = 500, message = "Mỗi yêu cầu nhập hàng có tối đa 500 dòng.")
        List<@NotNull @Valid PurchaseOrderLineRequest> lines,

        @NotBlank(message = "Vui lòng nhập lý do thay đổi.")
        @Size(max = 255, message = "Lý do tối đa 255 ký tự.")
        String reason) {
}
