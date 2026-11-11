package fu.hl.g64.motorflow.transfer.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.LowStockSuggestionResponse;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentCreateRequest;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentDetailResponse;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentRowResponse;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentSearch;
import fu.hl.g64.motorflow.transfer.dto.ToPurchaseRequest;

import java.util.List;
import java.util.Map;

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

    /** BR-55: sets per part what goes to the Owner for purchasing, and tells the Owner. */
    ReplenishmentDetailResponse toPurchase(AuthenticatedUser user, long id, ToPurchaseRequest request);

    /**
     * UC-40 with BR-55, inside the Owner's PO creation. Locks the request so that two POs cannot be bought for it at
     * once. It must belong to the receiving warehouse, be open, have no PO that is not cancelled, and the PO may only
     * buy its parts. With {@code includeUnallocated}, what no transfer order has planned yet is passed to purchase too,
     * and the PO must then buy exactly what is passed to purchase, per part.
     *
     * @param quantities the PO's quantity per part, in line order
     */
    void prepareForPurchase(AuthenticatedUser owner, long id, long storeId, Map<Long, Integer> quantities,
                            boolean includeUnallocated);
}
