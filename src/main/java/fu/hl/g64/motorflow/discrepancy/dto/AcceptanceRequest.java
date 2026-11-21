package fu.hl.g64.motorflow.discrepancy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * GVHD 20/11 (PR 5): a Branch Manager asks Owner to take held goods into stock instead of returning them: dented goods
 * ({@code DAMAGED}) as the part's "-M" variant, or goods delivered by mistake ({@code WRONG_ITEM}) as the part they
 * really are ({@code productId}, required for WRONG_ITEM since góp ý UI 21/11, MSG334; ignored for DAMAGED). {@code quantity} is at most what is held and not decided
 * for that kind (MSG309).
 */
public record AcceptanceRequest(
        @NotNull(message = "Vui lòng chọn loại hàng.")
        Type type,

        Long productId,

        @NotNull(message = "Vui lòng nhập số lượng.")
        @Positive(message = "Số lượng phải lớn hơn 0.")
        @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000.")
        Integer quantity,

        @Size(max = 255, message = "Ghi chú tối đa 255 ký tự.")
        String note) {

    public enum Type {
        DAMAGED, WRONG_ITEM
    }
}
