package fu.hl.g64.motorflow.inventory.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.inventory.dto.LowStockThresholdRequest;
import fu.hl.g64.motorflow.inventory.dto.MovementSearch;
import fu.hl.g64.motorflow.inventory.dto.StockAvailabilityResponse;
import fu.hl.g64.motorflow.inventory.dto.StockDetailResponse;
import fu.hl.g64.motorflow.inventory.dto.StockMovementResponse;
import fu.hl.g64.motorflow.inventory.dto.StockRowResponse;
import fu.hl.g64.motorflow.inventory.dto.StockSearch;
import fu.hl.g64.motorflow.inventory.dto.StorageLocationRequest;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.util.Collection;
import java.util.List;

/**
 * UC-48 to UC-52: looking at stock and its settings. A Branch Manager sees their own warehouse, the Owner every
 * warehouse (BR-03); purchase and cost values are shown to the Owner only (BR-72). Stock quantities change only
 * through {@link InventoryPostingService}.
 */
public interface InventoryService {

    /** UC-48: stock by part with low-stock and near-expiry warnings, ordered by store then SKU. */
    PageResponse<StockRowResponse> listStock(AuthenticatedUser user, StockSearch filter, int page, int size);

    /** UC-48: one part in one warehouse, split by lot and status. */
    StockDetailResponse stockDetail(AuthenticatedUser user, long storeId, long productId);

    /** UC-49: sellable stock of the parts in every active warehouse, to choose a transfer source. */
    List<StockAvailabilityResponse> availability(AuthenticatedUser user, Collection<Long> productIds);

    /** UC-50: movements newest first, each traceable to its lot and source document. */
    PageResponse<StockMovementResponse> movements(AuthenticatedUser user, MovementSearch filter, int page, int size);

    /** UC-51: shelf and bin of a part in a warehouse. */
    StockRowResponse updateLocation(AuthenticatedUser user, long storeId, long productId, StorageLocationRequest request);

    /** UC-52: minimum sellable stock before the low-stock warning. */
    StockRowResponse updateLowStockThreshold(AuthenticatedUser user, long storeId, long productId,
                                             LowStockThresholdRequest request);
}
