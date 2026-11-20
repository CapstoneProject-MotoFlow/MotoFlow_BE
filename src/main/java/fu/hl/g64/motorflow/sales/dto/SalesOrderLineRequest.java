package fu.hl.g64.motorflow.sales.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * One part the customer asks for and how many (UC-53 step 4). A part on two lines is MSG158.
 * <p>GVHD 20/11, on a new order only: {@code nowQty} is what the customer takes now (0..quantity, MSG305; the whole
 * quantity when left out) and {@code waitForRest} whether the rest waits on a waiting order (true when left out). The
 * store sells at most what it can now; with {@code waitForRest} everything else waits, without it the units not taken
 * are declined and a unit taken but no longer in stock is MSG171. A line with nothing taken now and nothing waiting is
 * MSG306.</p>
 */
public record SalesOrderLineRequest(
        @NotNull(message = "Vui lòng chọn phụ tùng.")
        Long productId,

        @NotNull(message = "Vui lòng nhập số lượng.")
        @Positive(message = "Số lượng phải lớn hơn 0.")
        @Max(value = 100_000, message = "Số lượng tối đa 100.000.")
        Integer quantity,

        @PositiveOrZero(message = "Số lấy ngay không được âm.")
        Integer nowQty,

        Boolean waitForRest) {

    public SalesOrderLineRequest(Long productId, Integer quantity) {
        this(productId, quantity, null, null);
    }

    /** What the customer takes now: the whole quantity unless told otherwise. */
    public int takenNow() {
        return nowQty == null ? quantity : nowQty;
    }

    public boolean waits() {
        return waitForRest == null || waitForRest;
    }
}
