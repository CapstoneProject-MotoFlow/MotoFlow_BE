package fu.hl.g64.motorflow.receiving.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.receiving.dto.AssignedReceiptResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/** Goods Receipt Notes (GRN) of supplier deliveries. */
public interface GoodsReceiptService {

    /**
     * UC-39 for Staff: the supplier deliveries whose inspection the user holds and has not finished, oldest
     * assignment first. A Branch Manager who took an inspection themself (UC-86) sees it here too.
     */
    PageResponse<AssignedReceiptResponse> assignedToMe(AuthenticatedUser user, int page, int size);
}
