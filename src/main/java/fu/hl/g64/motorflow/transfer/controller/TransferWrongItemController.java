package fu.hl.g64.motorflow.transfer.controller;

import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.PendingWrongItemResponse;
import fu.hl.g64.motorflow.transfer.dto.RejectWrongItemRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderDetailResponse;
import fu.hl.g64.motorflow.transfer.dto.WrongItemRequest;
import fu.hl.g64.motorflow.transfer.service.TransferWrongItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Transfer wrong items", description = "Plan 23/11: wrong items received on a transfer")
@RestController
@RequestMapping("/api/v1/transfer-orders")
@RequiredArgsConstructor
public class TransferWrongItemController {

    private final TransferWrongItemService wrongItems;

    @Operation(summary = "PM 23/11: the receiving store's BM or the Owner records a phiếu nhận hàng giao sai (the part "
            + "received, quantity, expiry, photos). Nothing moves until the source store's BM confirms it (GVHD 24/11)")
    @PostMapping("/{orderId}/wrong-items")
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
    public TransferOrderDetailResponse record(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long orderId,
                                              @Valid @RequestBody WrongItemRequest request) {
        return wrongItems.record(user, orderId, request);
    }

    @Operation(summary = "GVHD 24/11: phiếu nhận hàng giao sai awaiting the source, with the source lots it would take "
            + "now and the photos. A BM sees those of orders sent from their store; the Owner sees all, to view only")
    @GetMapping("/wrong-items/pending")
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
    public List<PendingWrongItemResponse> pending(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return wrongItems.pending(user, page, size);
    }

    @Operation(summary = "GVHD 24/11: the source store's BM confirms a phiếu nhận hàng giao sai: the part leaves the "
            + "source's sellable stock for the destination. MSG339/MSG341 when the source cannot give it; MSG05 for "
            + "anyone else, the Owner included")
    @PostMapping("/{orderId}/wrong-items/{eventId}/confirm")
    @PreAuthorize("hasRole('BRANCH_MANAGER')")
    public TransferOrderDetailResponse confirm(@AuthenticationPrincipal AuthenticatedUser user,
                                               @PathVariable long orderId, @PathVariable long eventId) {
        return wrongItems.confirm(user, orderId, eventId);
    }

    @Operation(summary = "GVHD 24/11: the source store's BM rejects a phiếu nhận hàng giao sai with a reason (MSG361). "
            + "Nothing moves; the wrong items are undecided again and the receiving store's BM is told")
    @PostMapping("/{orderId}/wrong-items/{eventId}/reject")
    @PreAuthorize("hasRole('BRANCH_MANAGER')")
    public TransferOrderDetailResponse reject(@AuthenticationPrincipal AuthenticatedUser user,
                                              @PathVariable long orderId, @PathVariable long eventId,
                                              @Valid @RequestBody(required = false) RejectWrongItemRequest request) {
        return wrongItems.reject(user, orderId, eventId, request);
    }
}
