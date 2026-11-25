package fu.hl.g64.motorflow.discrepancy.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * GVHD 24/11: the Owner approves the directions Branch Managers proposed for cases of one receipt; {@code caseIds}
 * left empty approves every case awaiting the Owner.
 */
public record DecisionApprovalRequest(
        @Size(max = 500, message = "Tối đa 500 dòng.")
        List<@NotNull Long> caseIds) {
}
