package fu.hl.g64.motorflow.transfer.service;

import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentCreateRequest;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentDetailResponse;

/** UC-68 to UC-70: a store asks for more stock. Coordination (UC-71) moves the request on from there. */
public interface ReplenishmentRequestService {

    /** UC-68: saves the request awaiting coordination and tells the central warehouse Branch Manager and the Owner. */
    ReplenishmentDetailResponse create(AuthenticatedUser user, ReplenishmentCreateRequest request);
}
