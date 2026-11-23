package fu.hl.g64.motorflow.transfer.controller;

import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.PendingWrongItemResponse;
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
            + "received, quantity, expiry). Nothing moves until the Owner confirms it")
    @PostMapping("/{orderId}/wrong-items")
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
    public TransferOrderDetailResponse record(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long orderId,
                                              @Valid @RequestBody WrongItemRequest request) {
        return wrongItems.record(user, orderId, request);
    }

    @Operation(summary = "Owner: phiếu nhận hàng giao sai awaiting confirmation, with the source lots it would take now")
    @GetMapping("/wrong-items/pending")
    @PreAuthorize("hasRole('OWNER')")
    public List<PendingWrongItemResponse> pending(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return wrongItems.pending(user, page, size);
    }

    @Operation(summary = "Owner confirms a phiếu nhận hàng giao sai: the part leaves the source's sellable stock for the "
            + "destination. MSG339/MSG341 when the source cannot give it. There is no rejection")
    @PostMapping("/{orderId}/wrong-items/{eventId}/confirm")
    @PreAuthorize("hasRole('OWNER')")
    public TransferOrderDetailResponse confirm(@AuthenticationPrincipal AuthenticatedUser user,
                                               @PathVariable long orderId, @PathVariable long eventId) {
        return wrongItems.confirm(user, orderId, eventId);
    }
}
