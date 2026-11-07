package fu.hl.g64.motorflow.transfer.service;

import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderCreateRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderResponse;

/** UC-71 to UC-77: stock moved between warehouses, coordinated by the Owner or the central warehouse Branch Manager. */
public interface TransferOrderService {

    /** UC-71: saves the order awaiting dispatch and tells the source Branch Manager. Reserves and moves no stock. */
    TransferOrderResponse create(AuthenticatedUser user, TransferOrderCreateRequest request);
}
