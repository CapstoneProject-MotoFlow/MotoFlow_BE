package fu.hl.g64.motorflow.inventory.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.inventory.dto.ExpiryWarningRequest;
import fu.hl.g64.motorflow.inventory.dto.ExpiryWarningResponse;
import fu.hl.g64.motorflow.inventory.dto.LotDetailResponse;
import fu.hl.g64.motorflow.inventory.dto.LotListResponse;
import fu.hl.g64.motorflow.inventory.dto.LotSearch;
import fu.hl.g64.motorflow.inventory.dto.LotTab;
import fu.hl.g64.motorflow.inventory.dto.LowStockThresholdRequest;
import fu.hl.g64.motorflow.inventory.dto.MovementSearch;
import fu.hl.g64.motorflow.inventory.dto.StockAvailabilityResponse;
import fu.hl.g64.motorflow.inventory.dto.StockDetailResponse;
import fu.hl.g64.motorflow.inventory.dto.StockMovementResponse;
import fu.hl.g64.motorflow.inventory.dto.StockRowResponse;
import fu.hl.g64.motorflow.inventory.dto.StockSearch;
import fu.hl.g64.motorflow.inventory.dto.StorageLocationRequest;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.service.ExpiryWarningService;
import fu.hl.g64.motorflow.inventory.service.InventoryService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Inventory", description = "UC-47 to UC-51, UC-97, UC-98 (Owner, Branch Manager), UC-104 (Owner)")
@RestController
@RequestMapping("/api/v1/inventory")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final ExpiryWarningService expiryWarningService;

    @Operation(summary = "Stock by part and status with low-stock and near-expiry warnings; stock value for the Owner "
            + "only (UC-47). Without storeId: own warehouse, or every warehouse for the Owner")
    @GetMapping("/stock")
    public PageResponse<StockRowResponse> listStock(@AuthenticationPrincipal AuthenticatedUser user,
                                                    @RequestParam(required = false) Long storeId,
                                                    @RequestParam(required = false) String q,
                                                    @RequestParam(required = false) Long categoryId,
                                                    @RequestParam(defaultValue = "false") boolean lowStockOnly,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return inventoryService.listStock(user, new StockSearch(storeId, q, categoryId, lowStockOnly), page, size);
    }

    @Operation(summary = "One part in one warehouse split by lot and status (UC-47)")
    @GetMapping("/stock/{storeId}/{productId}")
    public StockDetailResponse stockDetail(@AuthenticationPrincipal AuthenticatedUser user,
                                           @PathVariable long storeId, @PathVariable long productId) {
        return inventoryService.stockDetail(user, storeId, productId);
    }

    @Operation(summary = "Sellable stock of the parts in every active warehouse (UC-48, Owner or central warehouse BM)")
    @GetMapping("/availability")
    public List<StockAvailabilityResponse> availability(@AuthenticationPrincipal AuthenticatedUser user,
                                                        @RequestParam List<Long> productIds) {
        return inventoryService.availability(user, productIds);
    }

    @Operation(summary = "Stock movements, newest first, with lot and source document (UC-49)")
    @GetMapping("/movements")
    public PageResponse<StockMovementResponse> movements(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long lotId,
            @RequestParam(required = false) MovementType movementType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return inventoryService.movements(user, new MovementSearch(storeId, productId, lotId, movementType, from, to),
                page, size);
    }

    @Operation(summary = "Lots of a warehouse by tab, without prices (UC-97). Without storeId: own warehouse, or every "
            + "warehouse for the Owner. A Branch Manager, central warehouse included, sees their own warehouse only")
    @GetMapping("/lots")
    public LotListResponse listLots(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "NEAR_EXPIRY") LotTab tab,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) LotSourceType source,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedTo,
            @RequestParam(required = false) Long storeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return inventoryService.listLots(user, new LotSearch(tab, q, supplierId, source, receivedFrom, receivedTo, storeId),
                page, size);
    }

    @Operation(summary = "One lot as seen from a warehouse (UC-98). A Branch Manager sees their own warehouse; the Owner "
            + "storeId, or without it the warehouse that first received the lot. Purchase price for the Owner only")
    @GetMapping("/lots/{lotId}")
    public LotDetailResponse lotDetail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long lotId,
                                       @RequestParam(required = false) Long storeId) {
        return inventoryService.lotDetail(user, lotId, storeId);
    }

    @Operation(summary = "Update the shelf and bin of a part in a warehouse (UC-50)")
    @PutMapping("/stock/{storeId}/{productId}/location")
    public StockRowResponse updateLocation(@AuthenticationPrincipal AuthenticatedUser user,
                                           @PathVariable long storeId, @PathVariable long productId,
                                           @Valid @RequestBody StorageLocationRequest request) {
        return inventoryService.updateLocation(user, storeId, productId, request);
    }

    @Operation(summary = "Set or clear the low-stock threshold of a part in a warehouse (UC-51)")
    @PutMapping("/stock/{storeId}/{productId}/low-stock-threshold")
    public StockRowResponse updateLowStockThreshold(@AuthenticationPrincipal AuthenticatedUser user,
                                                    @PathVariable long storeId, @PathVariable long productId,
                                                    @Valid @RequestBody LowStockThresholdRequest request) {
        return inventoryService.updateLowStockThreshold(user, storeId, productId, request);
    }

    @Operation(summary = "The expiry warning days in effect, the version waiting and the history (UC-104, Owner)")
    @GetMapping("/expiry-warning")
    @PreAuthorize("hasRole('OWNER')")
    public ExpiryWarningResponse expiryWarning() {
        return expiryWarningService.expiryWarning();
    }

    @Operation(summary = "Set the expiry warning days from a date, today or later (UC-104, Owner)")
    @PutMapping("/expiry-warning")
    @PreAuthorize("hasRole('OWNER')")
    public ExpiryWarningResponse setExpiryWarning(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @Valid @RequestBody ExpiryWarningRequest request) {
        return expiryWarningService.setExpiryWarning(user, request);
    }
}
