package fu.hl.g64.motorflow.transfer.controller;

import fu.hl.g64.motorflow.receiving.dto.ExcessDecisionRequest;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.TransferReceiptResponse;
import fu.hl.g64.motorflow.transfer.service.TransferExcessService;
import fu.hl.g64.motorflow.transfer.service.TransferExcessService.PendingReceipt;
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

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('OWNER')")
@RequiredArgsConstructor
public class TransferExcessController {

    private final TransferExcessService excess;

    @GetMapping("/goods-receipt-notes/transfer-excess")
    public List<PendingReceipt> pending(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return excess.pending(page, size);
    }

    @PostMapping("/transfer-orders/{orderId}/receipts/{receiptId}/excess-decision")
    public TransferReceiptResponse decideExcess(@AuthenticationPrincipal AuthenticatedUser owner,
                                               @PathVariable long orderId, @PathVariable long receiptId,
                                               @Valid @RequestBody ExcessDecisionRequest request) {
        return excess.decideExcess(owner, orderId, receiptId, request);
    }
}
