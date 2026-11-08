package fu.hl.g64.motorflow.stockcount.service;

import fu.hl.g64.motorflow.inventory.entity.enums.StockCountRequestStatus;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.stockcount.dto.CountRequestCreateRequest;
import fu.hl.g64.motorflow.stockcount.dto.CountRequestResponse;
import fu.hl.g64.motorflow.stockcount.dto.StoreOptionResponse;

import java.util.List;

/** UC-105 Request Stock Count (R4 II.1). Only the Owner requests and cancels; a Branch Manager sees their store's. */
public interface StockCountRequestService {

    /** ACTIVE stores the Owner can ask to count; MSG05 for anyone else. */
    List<StoreOptionResponse> getRequestableStores(AuthenticatedUser user);

    /** Owner: every request; Branch Manager: their store's only (MSG05 for another {@code storeId}). */
    List<CountRequestResponse> getRequests(AuthenticatedUser user, Long storeId, StockCountRequestStatus status);

    /** Saves a PENDING request and notifies the store's Branch Managers. */
    CountRequestResponse create(AuthenticatedUser user, CountRequestCreateRequest request);

    /** Cancels a PENDING request and notifies the store's Branch Managers. */
    CountRequestResponse cancel(AuthenticatedUser user, long id);
}
