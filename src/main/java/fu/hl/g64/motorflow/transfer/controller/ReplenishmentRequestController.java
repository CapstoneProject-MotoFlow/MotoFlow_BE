package fu.hl.g64.motorflow.transfer.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.LowStockSuggestionResponse;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentCreateRequest;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentDetailResponse;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentRowResponse;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentSearch;
import fu.hl.g64.motorflow.transfer.dto.ToPurchaseRequest;
import fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus;
import fu.hl.g64.motorflow.transfer.service.ReplenishmentRequestService;
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

@Tag(name = "Replenishment requests", description = "UC-68 to UC-70 (Owner, Branch Manager)")
@RestController
@RequestMapping("/api/v1/replenishment-requests")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
@RequiredArgsConstructor
public class ReplenishmentRequestController {

    private final ReplenishmentRequestService service;

    @Operation(summary = "Create a replenishment request (UC-68). A Branch Manager requests for their own store; the "
            + "Owner chooses the store, which may still be planned")
    @PostMapping
    public ResponseEntity<ReplenishmentDetailResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                              @Valid @RequestBody ReplenishmentCreateRequest request) {
        ReplenishmentDetailResponse created = service.create(user, request);
        return ResponseEntity.created(URI.create("/api/v1/replenishment-requests/" + created.id())).body(created);
    }

    @Operation(summary = "Parts of the store below their low-stock threshold, leaving out those an unclosed request "
            + "of the store already asks for. Store rules are those of creating a request")
    @GetMapping("/low-stock-suggestions")
    public List<LowStockSuggestionResponse> lowStockSuggestions(@AuthenticationPrincipal AuthenticatedUser user,
                                                                @RequestParam(required = false) Long storeId) {
        return service.lowStockSuggestions(user, storeId);
    }

    @Operation(summary = "List replenishment requests (UC-69), those awaiting coordination first, then newest. A "
            + "Branch Manager sees their store; the central warehouse Branch Manager and the Owner see every store")
    @GetMapping
    public PageResponse<ReplenishmentRowResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) ReplenishmentRequestStatus status,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(user, new ReplenishmentSearch(status, storeId, from, to), page, size);
    }

    @Operation(summary = "One replenishment request (UC-70) with what was planned and received per part, and the "
            + "transfer orders and POs created for it. Same store scope as the list")
    @GetMapping("/{id}")
    public ReplenishmentDetailResponse detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return service.detail(user, id);
    }

    @Operation(summary = "Pass what no warehouse can supply to the Owner for purchasing (BR-55). Owner or the central "
            + "warehouse Branch Manager. Parts left out keep their quantity")
    @PutMapping("/{id}/to-purchase")
    public ReplenishmentDetailResponse toPurchase(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                                  @Valid @RequestBody ToPurchaseRequest request) {
        return service.toPurchase(user, id, request);
    }
}
