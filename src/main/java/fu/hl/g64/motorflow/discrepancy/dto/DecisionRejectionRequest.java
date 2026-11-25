package fu.hl.g64.motorflow.discrepancy.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * GVHD 24/11: the Owner sends proposed directions back, with a reason (MSG356 when blank); {@code caseIds} left empty
 * sends back every case awaiting the Owner. The cases are undecided again.
 */
public record DecisionRejectionRequest(
        @Size(max = 500, message = "Tối đa 500 dòng.")
        List<@NotNull Long> caseIds,

        @Size(max = 255, message = "Lý do tối đa 255 ký tự.")
        String reason) {
}
