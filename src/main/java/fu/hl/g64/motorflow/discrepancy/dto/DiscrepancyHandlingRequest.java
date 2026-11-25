package fu.hl.g64.motorflow.discrepancy.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import fu.hl.g64.motorflow.discrepancy.entity.enums.DecisionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * UC-46 steps 3-4: the handling agreed with the supplier for the cases of one receipt. For each case, the quantity
 * to return to the supplier (rejected excess included), whose goods move to awaiting return to supplier, and apart
 * from it the quantity the supplier replaces (BR-27): a part may be replaced without going back. The replacement is
 * at most the case's replacementLimitQty, and at least the short quantity (only Owner waives a shortage) and what
 * was already replaced, waived or is on its way. Left empty, it grows by the wrong or damaged units newly agreed to
 * go back, within that limit.
 */
public record DiscrepancyHandlingRequest(
        @NotEmpty(message = "Vui lòng nhập hướng xử lý cho ít nhất một phụ tùng.")
        @Size(max = 500, message = "Tối đa 500 dòng.")
        List<@NotNull @Valid Line> lines,

        @Size(max = 255, message = "Ghi chú tối đa 255 ký tự.")
        String note) {

    /**
     * Góp ý UI 21/11: {@code wrongReturnQty} and {@code damagedReturnQty} are the wrong items and the damaged goods to
     * return, each at most what of that kind is still held (MSG35) and at least what of it went back already
     * (MSG134); one left empty keeps what was agreed. {@code returnRequiredQty} is the older single figure (rejected
     * excess included), split as before (wrong items first, then damaged goods); it is used only when both kinds are
     * empty.
     * <p>
     * GVHD 24/11 (V26): {@code decisionType} is the direction agreed with the supplier; left empty it is
     * RETURN_AND_REPLACE, as every handling was before. RETURN_NO_REPLACEMENT and ACCEPT_NO_REPLACEMENT take no
     * replacement (0 or empty, MSG354); ACCEPT_NO_REPLACEMENT returns nothing and needs wrong or damaged goods.
     */
    public record Line(
            @NotNull(message = "Vui lòng chọn hồ sơ sai lệch.")
            Long caseId,

            @PositiveOrZero(message = "Số lượng không được âm.")
            @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000.")
            Integer wrongReturnQty,

            @PositiveOrZero(message = "Số lượng không được âm.")
            @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000.")
            Integer damagedReturnQty,

            @PositiveOrZero(message = "Số lượng không được âm.")
            @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000.")
            Integer returnRequiredQty,

            @PositiveOrZero(message = "Số lượng không được âm.")
            @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000.")
            Integer replacementRequiredQty,

            DecisionType decisionType) {

        /** The older form: case, whole return, replacement. */
        public Line(Long caseId, Integer returnRequiredQty, Integer replacementRequiredQty) {
            this(caseId, null, null, returnRequiredQty, replacementRequiredQty, null);
        }

        /** Before GVHD 24/11: return and replace. */
        public Line(Long caseId, Integer wrongReturnQty, Integer damagedReturnQty, Integer returnRequiredQty,
                    Integer replacementRequiredQty) {
            this(caseId, wrongReturnQty, damagedReturnQty, returnRequiredQty, replacementRequiredQty, null);
        }

        /** The direction asked for: RETURN_AND_REPLACE when left empty. */
        @JsonIgnore
        public DecisionType direction() {
            return decisionType != null ? decisionType : DecisionType.RETURN_AND_REPLACE;
        }

        @JsonIgnore
        @AssertTrue(message = "Vui lòng nhập số lượng sẽ trả lại.")
        public boolean isReturnGiven() {
            // Accepting the goods returns nothing, so no figure is needed.
            return decisionType == DecisionType.ACCEPT_NO_REPLACEMENT || wrongReturnQty != null
                    || damagedReturnQty != null || returnRequiredQty != null;
        }
    }
}
