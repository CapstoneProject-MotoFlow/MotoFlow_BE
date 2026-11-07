package fu.hl.g64.motorflow.receivable.dto;

import fu.hl.g64.motorflow.partner.entity.enums.CreditStatus;
import fu.hl.g64.motorflow.partner.entity.enums.EventType;

import java.time.LocalDateTime;

/**
 * The customer's credit eligibility after a grant or revocation (UC-62 step 5, UC-63 step 5). {@code changed} is false
 * when nothing was recorded because the customer already had that status; {@code lastDecision} is then the decision
 * that stands. Debts are in VND over the whole chain (BR-53): revoking credit leaves them as they are (BR-43).
 */
public record CustomerCreditResponse(long customerId, String customerName, CreditStatus creditStatus, boolean changed,
                                     Decision lastDecision, long totalDebt, long overdueDebt) {

    /** Null {@code lastDecision} only for a customer nobody ever granted credit to. */
    public record Decision(EventType eventType, String reason, Long decidedById, String decidedBy,
                           LocalDateTime decidedAt) {
    }
}
