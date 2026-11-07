package fu.hl.g64.motorflow.transfer.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.LowStockSuggestionResponse;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentCreateRequest;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentDetailResponse;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentRowResponse;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentSearch;

import java.util.List;

/** UC-68 to UC-70: a store asks for more stock. Coordination (UC-71) moves the request on from there. */
public interface ReplenishmentRequestService {

    /** UC-68: saves the request awaiting coordination and tells the central warehouse Branch Manager and the Owner. */
    ReplenishmentDetailResponse create(AuthenticatedUser user, ReplenishmentCreateRequest request);

    /** Screen 7.1.2: the store's parts below threshold that no unclosed request of the store already asks for. */
    List<LowStockSuggestionResponse> lowStockSuggestions(AuthenticatedUser user, Long storeId);

    /** UC-69: a Branch Manager sees their store; the central warehouse Branch Manager and the Owner see every store. */
    PageResponse<ReplenishmentRowResponse> list(AuthenticatedUser user, ReplenishmentSearch search, int page, int size);

    /** UC-70: same scope as the list. */
    ReplenishmentDetailResponse detail(AuthenticatedUser user, long id);
}
