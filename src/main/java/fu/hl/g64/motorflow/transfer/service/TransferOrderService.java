package fu.hl.g64.motorflow.transfer.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.ClosingPreviewResponse;
import fu.hl.g64.motorflow.transfer.dto.DispatchPreviewResponse;
import fu.hl.g64.motorflow.transfer.dto.ExtraDispatchRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferDispatchRequest;
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

    /** UC-74: the Owner, the central warehouse Branch Manager and the source Branch Manager. Changes nothing. */
    DispatchPreviewResponse dispatchPreview(AuthenticatedUser user, long id);

    /**
     * UC-74: a source Branch Manager other than the preparer moves the prepared goods lot by lot from the source's
     * available stock into transit at the destination. Confirming an order already dispatched returns it unchanged.
     */
    TransferOrderDetailResponse dispatch(AuthenticatedUser user, long id, TransferDispatchRequest request);

    /**
     * UC-75: a source Branch Manager confirms how much of the excess received they sent. Only counted here; the stock
     * of both warehouses changes when the order is closed (BR-68).
     */
    TransferOrderDetailResponse confirmExtraDispatch(AuthenticatedUser user, long id, ExtraDispatchRequest request);

    /** UC-77: the Owner and the central warehouse Branch Manager. Changes nothing. */
    ClosingPreviewResponse closingPreview(AuthenticatedUser user, long id);

    /**
     * UC-77: the Owner or the central warehouse Branch Manager closes an order whose every dispatched unit has a final
     * result, posting the confirmed excess from the source to the destination, and closes the replenishment request
     * once all its orders are closed and every part was received or passed to purchasing. Closing an order already
     * closed returns it unchanged.
     */
    TransferOrderDetailResponse close(AuthenticatedUser user, long id);
}
