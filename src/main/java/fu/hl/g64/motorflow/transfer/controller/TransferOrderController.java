package fu.hl.g64.motorflow.transfer.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.DispatchPreviewResponse;
import fu.hl.g64.motorflow.transfer.dto.ExtraDispatchRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferDispatchRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderCreateRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderDetailResponse;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderRowResponse;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderSearch;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import fu.hl.g64.motorflow.transfer.service.TransferOrderService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;

@Tag(name = "Transfer orders", description = "UC-71 to UC-77")
@RestController
@RequestMapping("/api/v1/transfer-orders")
@RequiredArgsConstructor
public class TransferOrderController {

    private final TransferOrderService service;

    @Operation(summary = "Create a transfer order (UC-71). Owner or the central warehouse Branch Manager, from a "
            + "replenishment request or directly. Reserves no stock and posts no movement")
    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
    public ResponseEntity<TransferOrderDetailResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                              @Valid @RequestBody TransferOrderCreateRequest request) {
        TransferOrderDetailResponse created = service.create(user, request);
        return ResponseEntity.created(URI.create("/api/v1/transfer-orders/" + created.id())).body(created);
    }

    @Operation(summary = "List transfer orders (UC-72), unclosed first, then newest. Owner and the central warehouse "
            + "Branch Manager see all, a Branch Manager those from or to their store, Staff those they prepare or "
            + "inspect. from and to are created dates")
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER', 'STAFF')")
    public PageResponse<TransferOrderRowResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) TransferOrderStatus status,
            @RequestParam(required = false) Long sourceStoreId,
            @RequestParam(required = false) Long destStoreId,
            @RequestParam(required = false) Long replenishmentRequestId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(user, new TransferOrderSearch(status, sourceStoreId, destStoreId, replenishmentRequestId,
                from, to), page, size);
    }

    @Operation(summary = "One transfer order (UC-73) with dispatched lots, receipts and discrepancies. Staff see only "
            + "the parts and quantities; only the Owner sees lot cost")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER', 'STAFF')")
    public TransferOrderDetailResponse detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return service.detail(user, id);
    }

    @Operation(summary = "What the dispatch would post (UC-74): prepared quantities and the lots it would take, FEFO "
            + "then FIFO, skipping expired lots. Owner, central warehouse Branch Manager, source Branch Manager")
    @GetMapping("/{id}/dispatch-preview")
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
    public DispatchPreviewResponse dispatchPreview(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @PathVariable long id) {
        return service.dispatchPreview(user, id);
    }

    @Operation(summary = "Confirm the dispatch (UC-74). Only a source Branch Manager who did not prepare the goods; "
            + "every line, each equal to its prepared quantity. Sent again after it succeeded, returns the order")
    @PostMapping("/{id}/dispatch")
    @PreAuthorize("hasRole('BRANCH_MANAGER')")
    public TransferOrderDetailResponse dispatch(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                                @Valid @RequestBody TransferDispatchRequest request) {
        return service.dispatch(user, id, request);
    }

    @Operation(summary = "Confirm the extra dispatch (UC-75). A source Branch Manager confirms, per part, how much of "
            + "the excess the destination received was really sent; it adds up and posts nothing until closing. "
            + "A line confirmed as 0 needs a reason")
    @PostMapping("/{id}/extra-dispatch")
    @PreAuthorize("hasRole('BRANCH_MANAGER')")
    public TransferOrderDetailResponse confirmExtraDispatch(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @PathVariable long id,
                                                            @Valid @RequestBody ExtraDispatchRequest request) {
        return service.confirmExtraDispatch(user, id, request);
    }
}
