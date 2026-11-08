package fu.hl.g64.motorflow.inventory.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.inventory.dto.LotDetailResponse;
import fu.hl.g64.motorflow.inventory.dto.LotListResponse;
import fu.hl.g64.motorflow.inventory.dto.LotSearch;
import fu.hl.g64.motorflow.inventory.dto.LowStockThresholdRequest;
import fu.hl.g64.motorflow.inventory.dto.MovementSearch;
import fu.hl.g64.motorflow.inventory.dto.PastDateStockResponse;
import fu.hl.g64.motorflow.inventory.dto.StockAvailabilityResponse;
import fu.hl.g64.motorflow.inventory.dto.StockByProductResponse;
import fu.hl.g64.motorflow.inventory.dto.StockDetailResponse;
import fu.hl.g64.motorflow.inventory.dto.StockMovementResponse;
import fu.hl.g64.motorflow.inventory.dto.StockRowResponse;
import fu.hl.g64.motorflow.inventory.dto.StockSearch;
import fu.hl.g64.motorflow.inventory.dto.StockSummaryResponse;
import fu.hl.g64.motorflow.inventory.dto.StorageLocationRequest;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * UC-47 to UC-51: looking at stock and its settings. A Branch Manager sees their own warehouse, the Owner every
 * warehouse (BR-03); purchase and cost values are shown to the Owner only (BR-72). Stock quantities change only
 * through {@link InventoryPostingService}.
 */
public interface InventoryService {

    /** UC-47: stock by part with low-stock and near-expiry warnings, ordered by store then part. */
    PageResponse<StockRowResponse> listStock(AuthenticatedUser user, StockSearch filter, int page, int size);

    /**
     * UC-47, Owner: one row per part with totals over every warehouse in scope, ordered by SKU. A part is listed when
     * at least one warehouse matches the filters; the totals still cover all its warehouses.
     */
    PageResponse<StockByProductResponse> stockByProduct(AuthenticatedUser user, StockSearch filter, int page, int size);

    /** UC-47: the counts above the list, over everything in scope and not only the current page. */
    StockSummaryResponse stockSummary(AuthenticatedUser user, StockSearch scope);

    /** UC-47: the storage areas in use, A to Z, for the area filter. */
    List<String> storageAreas(AuthenticatedUser user, Long storeId);

    /** UC-47: one part in one warehouse, split by lot and status. */
    StockDetailResponse stockDetail(AuthenticatedUser user, long storeId, long productId);

    /** UC-48: sellable stock of the parts in every active warehouse, to choose a transfer source. */
    List<StockAvailabilityResponse> availability(AuthenticatedUser user, Collection<Long> productIds);

    /** UC-49: movements newest first, each traceable to its lot and source document. */
    PageResponse<StockMovementResponse> movements(AuthenticatedUser user, MovementSearch filter, int page, int size);

    /** UC-50: shelf and bin of a part in a warehouse. */
    StockRowResponse updateLocation(AuthenticatedUser user, long storeId, long productId, StorageLocationRequest request);

    /** UC-51: minimum available stock before the low-stock warning. */
    StockRowResponse updateLowStockThreshold(AuthenticatedUser user, long storeId, long productId,
                                             LowStockThresholdRequest request);

    /** UC-97: the lots of a warehouse by tab; a Branch Manager sees their own warehouse only, central included. */
    LotListResponse listLots(AuthenticatedUser user, LotSearch filter, int page, int size);

    /**
     * UC-98: one lot as seen from a warehouse: the Branch Manager's own, or for the Owner {@code storeId} and without
     * it the warehouse that first received the lot.
     */
    LotDetailResponse lotDetail(AuthenticatedUser user, long lotId, Long storeId);

    /**
     * UC-103: closing stock of each part in one warehouse at the end of a past date. The Owner must name the warehouse;
     * a Branch Manager sees their own only.
     */
    PastDateStockResponse pastDateStock(AuthenticatedUser user, LocalDate date, Long storeId, String q, Long categoryId,
                                        boolean includeZero, int page, int size);
}
