package fu.hl.g64.motorflow.stockcount.controller;

import fu.hl.g64.motorflow.inventory.entity.enums.CountType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.stockcount.dto.CountRequestResponse;
import fu.hl.g64.motorflow.stockcount.dto.StockCountApproveRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountCancelRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountConfirmRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountCreateRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountDetailResponse;
import fu.hl.g64.motorflow.stockcount.dto.StockCountFilter;
import fu.hl.g64.motorflow.stockcount.dto.StockCountLinesSaveRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountListResponse;
import fu.hl.g64.motorflow.stockcount.dto.StockCountRecountRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountSubmitRequest;
import fu.hl.g64.motorflow.stockcount.service.StockCountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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

@Tag(name = "Stock counts", description = "UC-90 to UC-95 (Owner, Branch Manager, the Staff member counting)")
@RestController
@RequestMapping("/api/v1/stock-counts")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER', 'STAFF')")
@RequiredArgsConstructor
public class StockCountController {

    private final StockCountService service;

    @Operation(summary = "The Owner's PENDING request to fill in a new count (UC-91, Branch Manager of its store)")
    @GetMapping("/prefill")
    public CountRequestResponse prefill(@AuthenticationPrincipal AuthenticatedUser user,
                                        @RequestParam long requestId) {
        return service.getPrefill(user, requestId);
    }

    @Operation(summary = "Create a year-end or cycle count of the Branch Manager's warehouse (UC-91)")
    @PostMapping
    public ResponseEntity<StockCountDetailResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                           @Valid @RequestBody StockCountCreateRequest request) {
        StockCountDetailResponse created = service.create(user, request);
        return ResponseEntity.created(URI.create("/api/v1/stock-counts/" + created.id())).body(created);
    }

    @Operation(summary = "List stock counts (UC-90). Owner: every store; Branch Manager: their warehouse, with the "
            + "Owner's pending requests; Staff: the counts given to them")
    @GetMapping
    public StockCountListResponse list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) CountType countType,
            @RequestParam(required = false) StockCountStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.getStockCounts(user, new StockCountFilter(storeId, countType, status, from, to),
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)));
    }

    @Operation(summary = "One stock count (UC-92). The counter sees no book quantity; only the Owner sees values")
    @GetMapping("/{id}")
    public StockCountDetailResponse detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return service.getStockCount(user, id);
    }

    @Operation(summary = "Cancel a count that never went to the Owner (UC-94, Branch Manager)")
    @PostMapping("/{id}/cancel")
    public StockCountDetailResponse cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                           @Valid @RequestBody StockCountCancelRequest request) {
        return service.cancel(user, id, request);
    }

    @Operation(summary = "Start counting; book quantities are taken now (UC-93, the counter)")
    @PostMapping("/{id}/start")
    public StockCountDetailResponse start(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return service.start(user, id);
    }

    @Operation(summary = "Save counted quantities as a draft (UC-93, the counter)")
    @PutMapping("/{id}/lines")
    public StockCountDetailResponse saveLines(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                              @Valid @RequestBody StockCountLinesSaveRequest request) {
        return service.saveLines(user, id, request);
    }

    @Operation(summary = "Send the counted quantities to the Branch Manager (UC-93, the counter)")
    @PostMapping("/{id}/submit")
    public StockCountDetailResponse submit(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                           @Valid @RequestBody(required = false) StockCountSubmitRequest request) {
        return service.submit(user, id, request == null ? new StockCountSubmitRequest(null) : request);
    }

    @Operation(summary = "Confirm the result (UC-94, Branch Manager): done if nothing differs, otherwise to the Owner")
    @PostMapping("/{id}/confirm")
    public StockCountDetailResponse confirm(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                            @Valid @RequestBody(required = false) StockCountConfirmRequest request) {
        return service.confirm(user, id, request == null ? new StockCountConfirmRequest(null, null) : request);
    }

    @Operation(summary = "Send lines back to the counter (UC-94, Branch Manager while counting, Owner while awaiting "
            + "approval)")
    @PostMapping("/{id}/recount")
    public StockCountDetailResponse recount(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                            @Valid @RequestBody StockCountRecountRequest request) {
        return service.requestRecount(user, id, request);
    }

    @Operation(summary = "Approve the differences and post them by lot into the count's period (UC-95, Owner)")
    @PostMapping("/{id}/approve")
    public StockCountDetailResponse approve(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                            @Valid @RequestBody(required = false) StockCountApproveRequest request) {
        return service.approve(user, id, request == null ? new StockCountApproveRequest(null) : request);
    }
}
