package fu.hl.g64.motorflow.sales.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.sales.dto.ConfirmSalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.PickingRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail;
import fu.hl.g64.motorflow.sales.dto.SalesOrderPreview;
import fu.hl.g64.motorflow.sales.dto.SalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderSearch;
import fu.hl.g64.motorflow.sales.dto.SalesOrderSummary;
import fu.hl.g64.motorflow.sales.dto.SalesOrderUpdateRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderVersion;
import fu.hl.g64.motorflow.sales.dto.SalesOrderView;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import fu.hl.g64.motorflow.sales.service.SalesOrderService;
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
import java.util.List;

@Tag(name = "Sales orders", description = "UC-52 list, UC-53 create, UC-54 detail, UC-55 update, UC-57 record "
        + "picked quantities, UC-58 confirm. Sellers are Owner and the store's Branch Manager; the picker is whoever "
        + "holds the picking task (UC-86). Nothing here changes stock. Amounts are in VND, rates in percent.")
@RestController
@RequestMapping("/api/v1/sales-orders")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
@RequiredArgsConstructor
public class SalesOrderController {

    private final SalesOrderService salesOrders;

    @Operation(summary = "Sales orders, newest first (UC-52); Staff see the ones they are picking")
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER', 'STAFF')")
    public PageResponse<SalesOrderSummary> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                @RequestParam(required = false) Long storeId,
                                                @RequestParam(required = false) SalesOrderView view,
                                                @RequestParam(required = false) SalesOrderStatus status,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(required = false)
                                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
                                                @RequestParam(required = false)
                                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return salesOrders.list(user, new SalesOrderSearch(storeId, view, status, q, createdFrom, createdTo), page, size);
    }

    @Operation(summary = "Prices, totals, discount threshold and stock of an order being filled in; nothing is saved "
            + "(UC-53 step 5)")
    @PostMapping("/preview")
    public SalesOrderPreview preview(@AuthenticationPrincipal AuthenticatedUser seller,
                                     @Valid @RequestBody SalesOrderRequest request) {
        return salesOrders.preview(seller, request);
    }

    @Operation(summary = "Create a sales order, waiting for picking (UC-53)")
    @PostMapping
    public ResponseEntity<SalesOrderDetail> create(@AuthenticationPrincipal AuthenticatedUser seller,
                                                   @Valid @RequestBody SalesOrderRequest request) {
        SalesOrderDetail created = salesOrders.create(seller, request);
        return ResponseEntity.created(URI.create("/api/v1/sales-orders/" + created.id())).body(created);
    }

    @Operation(summary = "One sales order (UC-54); the Staff member picking it sees no prices")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER', 'STAFF')")
    public SalesOrderDetail get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return salesOrders.get(user, id);
    }

    @Operation(summary = "Every version of the order as it was saved, oldest first")
    @GetMapping("/{id}/versions")
    public List<SalesOrderVersion> versions(@AuthenticationPrincipal AuthenticatedUser seller, @PathVariable long id) {
        return salesOrders.versions(seller, id);
    }

    @Operation(summary = "Change the order: saves a new version that must be confirmed again (UC-55, BR-36)")
    @PutMapping("/{id}")
    public SalesOrderDetail update(@AuthenticationPrincipal AuthenticatedUser seller, @PathVariable long id,
                                   @Valid @RequestBody SalesOrderUpdateRequest request) {
        return salesOrders.update(seller, id, request);
    }

    @Operation(summary = "Send the picked quantity of every line, or correct it before confirmation (UC-57)")
    @PutMapping("/{id}/picked-lines")
    @PreAuthorize("hasAnyRole('STAFF', 'BRANCH_MANAGER')")
    public SalesOrderDetail recordPicking(@AuthenticationPrincipal AuthenticatedUser picker, @PathVariable long id,
                                          @Valid @RequestBody PickingRequest request) {
        return salesOrders.recordPicking(picker, id, request);
    }

    @Operation(summary = "Set delivered quantities and confirm the price the customer agreed (UC-58)")
    @PostMapping("/{id}/confirm")
    public SalesOrderDetail confirm(@AuthenticationPrincipal AuthenticatedUser seller, @PathVariable long id,
                                    @Valid @RequestBody ConfirmSalesOrderRequest request) {
        return salesOrders.confirm(seller, id, request);
    }
}
