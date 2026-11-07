package fu.hl.g64.motorflow.transfer.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderCreateRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderDetailResponse;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderRowResponse;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderSearch;

/** UC-71 to UC-77: stock moved between warehouses, coordinated by the Owner or the central warehouse Branch Manager. */
public interface TransferOrderService {

    /** UC-71: saves the order awaiting dispatch and tells the source Branch Manager. Reserves and moves no stock. */
    TransferOrderDetailResponse create(AuthenticatedUser user, TransferOrderCreateRequest request);

    /**
     * UC-72: the Owner and the central warehouse Branch Manager see every order, a Branch Manager those from or to
     * their store, Staff those they prepare or inspect.
     */
    PageResponse<TransferOrderRowResponse> list(AuthenticatedUser user, TransferOrderSearch search, int page, int size);

    /** UC-73: same scope as the list. */
    TransferOrderDetailResponse detail(AuthenticatedUser user, long id);
}
