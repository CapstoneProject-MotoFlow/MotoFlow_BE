package fu.hl.g64.motorflow.transfer.service;

import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.TransferDecisionRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderDetailResponse;

/** UC-76: the Owner or the central warehouse Branch Manager decides how a missing quantity gets its final result. */
public interface TransferDiscrepancyService {

    /**
     * Records the decision in the order's history. A late delivery or a return is settled by its own receipt; a loss
     * links a STOCK_LOSS adjustment of the order, which settles the quantity only once applied (BR-66). Neither the
     * decision nor a make-up shipment lowers the unresolved quantity by itself (BR-60, BR-62).
     */
    TransferOrderDetailResponse decide(AuthenticatedUser user, long transferOrderId, TransferDecisionRequest request);
}
