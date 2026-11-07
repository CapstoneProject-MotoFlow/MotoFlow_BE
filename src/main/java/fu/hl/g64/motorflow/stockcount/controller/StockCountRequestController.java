package fu.hl.g64.motorflow.stockcount.controller;

import fu.hl.g64.motorflow.inventory.entity.enums.StockCountRequestStatus;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.stockcount.dto.CountRequestCreateRequest;
import fu.hl.g64.motorflow.stockcount.dto.CountRequestResponse;
import fu.hl.g64.motorflow.stockcount.dto.StoreOptionResponse;
import fu.hl.g64.motorflow.stockcount.service.StockCountRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@Tag(name = "Stock count requests", description = "UC-105 (Owner; Branch Manager reads their store's)")
@RestController
@RequestMapping("/api/v1/stock-count-requests")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
@RequiredArgsConstructor
public class StockCountRequestController {

    private final StockCountRequestService service;

    @Operation(summary = "ACTIVE stores the Owner can ask to count (UC-105)")
    @GetMapping("/stores")
    public List<StoreOptionResponse> stores(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.getRequestableStores(user);
    }

    @Operation(summary = "Stock count requests (UC-105). The Owner sees every store; a Branch Manager their own")
    @GetMapping
    public List<CountRequestResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                           @RequestParam(required = false) Long storeId,
                                           @RequestParam(required = false) StockCountRequestStatus status) {
        return service.getRequests(user, storeId, status);
    }

    @Operation(summary = "Ask a store's Branch Manager to count (UC-105, Owner)")
    @PostMapping
    public ResponseEntity<CountRequestResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                       @Valid @RequestBody CountRequestCreateRequest request) {
        CountRequestResponse created = service.create(user, request);
        return ResponseEntity.created(URI.create("/api/v1/stock-count-requests/" + created.id())).body(created);
    }

    @Operation(summary = "Cancel a PENDING stock count request (UC-105, Owner)")
    @PostMapping("/{id}/cancel")
    public CountRequestResponse cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return service.cancel(user, id);
    }
}
