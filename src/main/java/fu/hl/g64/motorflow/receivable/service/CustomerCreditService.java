package fu.hl.g64.motorflow.receivable.service;

import fu.hl.g64.motorflow.receivable.dto.CreditDecisionRequest;
import fu.hl.g64.motorflow.receivable.dto.CustomerCreditResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/**
 * Owner's credit eligibility decisions (BR-40). They are kept in audit_log as CREDIT_GRANTED and CREDIT_REVOKED, which
 * the customer profile (UC-28) shows as the credit history. Eligibility never approves credit for an order by itself
 * (BR-41, UC-61).
 */
public interface CustomerCreditService {

    /**
     * UC-62: the customer may buy on credit until Owner revokes it; granting again after a revocation is allowed.
     * A customer already eligible keeps the existing grant and nothing is recorded (Step 2_Already eligible).
     * MSG74 for an unknown customer.
     */
    CustomerCreditResponse grant(AuthenticatedUser owner, long customerId, CreditDecisionRequest request);

    /**
     * UC-63: no order of the customer may be completed on credit any more (BR-44); existing debts stay collectible
     * (BR-43). An already revoked customer keeps the existing decision and nothing is recorded (Step 4_Already
     * revoked). MSG146 for a customer never granted credit, MSG74 for an unknown customer.
     */
    CustomerCreditResponse revoke(AuthenticatedUser owner, long customerId, CreditDecisionRequest request);
}
