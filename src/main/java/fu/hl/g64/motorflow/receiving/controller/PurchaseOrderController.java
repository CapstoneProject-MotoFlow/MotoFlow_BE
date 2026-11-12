package fu.hl.g64.motorflow.receiving.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.receiving.dto.CancelPurchaseOrderRequest;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptDetail;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptRequest;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderDetail;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderReceivingView;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderRequest;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderSearch;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderSummary;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderUpdateRequest;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderView;
import fu.hl.g64.motorflow.receiving.service.GoodsReceiptService;
import fu.hl.g64.motorflow.receiving.service.PurchaseOrderService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;

@Tag(name = "Purchase orders", description = "UC-39 to UC-42: Owner creates, changes and cancels purchase orders "
        + "(yêu cầu nhập hàng); the Branch Manager of the receiving warehouse follows them")
@RestController
@RequestMapping("/api/v1/purchase-orders")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrders;
    private final GoodsReceiptService goodsReceipts;

    @Operation(summary = "Purchase orders of every warehouse (Owner) or of the user's warehouse (UC-39)",
            description = "q: PO number (with or without #) or supplier name or code. view: OPEN (default), "
                    + "FULLY_RECEIVED, CANCELLED or ALL. createdFrom and createdTo (yyyy-MM-dd): creation days; "
                    + "the views other than OPEN keep the last 30 days when none are given.")
    @GetMapping
    public PageResponse<PurchaseOrderSummary> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) PurchaseOrderView view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return purchaseOrders.list(user, new PurchaseOrderSearch(q, storeId, supplierId, view, createdFrom, createdTo),
                page, size);
    }

    @Operation(summary = "A purchase order with its lines, deliveries, discrepancies and history (UC-41)")
    @GetMapping("/{id}")
    public PurchaseOrderDetail get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return purchaseOrders.get(user, id);
    }

    @Operation(summary = "Create a purchase order and send it to the receiving warehouse (UC-40)")
    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<PurchaseOrderDetail> create(@AuthenticationPrincipal AuthenticatedUser owner,
                                                      @Valid @RequestBody PurchaseOrderRequest request) {
        PurchaseOrderDetail created = purchaseOrders.create(owner, request);
        return ResponseEntity.created(URI.create("/api/v1/purchase-orders/" + created.id())).body(created);
    }

    @Operation(summary = "Change the lines or delivery details of a purchase order that has no GRN yet (UC-42)")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public PurchaseOrderDetail update(@AuthenticationPrincipal AuthenticatedUser owner, @PathVariable long id,
                                      @Valid @RequestBody PurchaseOrderUpdateRequest request) {
        return purchaseOrders.update(owner, id, request);
    }

    @Operation(summary = "Record a delivery that arrived for the purchase order (UC-106)",
            description = "Only the person holding the PO's receiving task (SUPPLIER_RECEIVING, given by the Branch "
                    + "Manager, UC-86), usually a Staff member; MSG05 otherwise. Without replacementForCaseIds the GRN "
                    + "has one line per PO line still outstanding; with them it is a replacement delivery for those "
                    + "discrepancy cases of the PO. The receiving task is then done and the GRN's inspection goes to "
                    + "the same person. Prices are hidden except for Owner (BR-72).")
    @PostMapping("/{id}/goods-receipt-notes")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'STAFF')")
    public ResponseEntity<GoodsReceiptDetail> createReceipt(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @PathVariable long id,
                                                            @Valid @RequestBody GoodsReceiptRequest request) {
        GoodsReceiptDetail created = goodsReceipts.create(user, id, request);
        return ResponseEntity.created(URI.create("/api/v1/goods-receipt-notes/" + created.id())).body(created);
    }

    @Operation(summary = "What the person receiving the PO's goods needs to record the GRN, without any price",
            description = "Branch Manager of the receiving warehouse, or the Staff member holding the PO's receiving "
                    + "task (SUPPLIER_RECEIVING); MSG05 otherwise. Lines with what is still outstanding, and the "
                    + "discrepancy cases a replacement delivery may still bring goods for.")
    @GetMapping("/{id}/receiving-view")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'STAFF')")
    public PurchaseOrderReceivingView receivingView(@AuthenticationPrincipal AuthenticatedUser user,
                                                    @PathVariable long id) {
        return purchaseOrders.receivingView(user, id);
    }

    @Operation(summary = "Cancel a purchase order that has no GRN yet (UC-42)")
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('OWNER')")
    public PurchaseOrderDetail cancel(@AuthenticationPrincipal AuthenticatedUser owner, @PathVariable long id,
                                      @Valid @RequestBody CancelPurchaseOrderRequest request) {
        return purchaseOrders.cancel(owner, id, request.reason());
    }
}
