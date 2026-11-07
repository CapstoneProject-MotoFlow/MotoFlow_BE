package fu.hl.g64.motorflow.transfer.controller;

import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.TransferDecisionRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderDetailResponse;
import fu.hl.g64.motorflow.transfer.dto.TransferReceiptResponse;
import fu.hl.g64.motorflow.transfer.dto.TransferReturnRequest;
import fu.hl.g64.motorflow.transfer.service.TransferDiscrepancyService;
import fu.hl.g64.motorflow.transfer.service.TransferReceiptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Tag(name = "Transfer discrepancies", description = "UC-76")
@RestController
@RequestMapping("/api/v1/transfer-orders/{orderId}")
@RequiredArgsConstructor
public class TransferDiscrepancyController {

    private final TransferDiscrepancyService discrepancies;
    private final TransferReceiptService receipts;

    @Operation(summary = "Decide how a missing quantity is handled (UC-76): late delivery, return to the source, or a "
            + "loss linked to a STOCK_LOSS adjustment of the order. Owner or central warehouse Branch Manager. The "
            + "quantity is settled only by the receipt or the applied adjustment")
    @PostMapping("/discrepancy/decisions")
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
    public TransferOrderDetailResponse decide(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long orderId,
                                              @Valid @RequestBody TransferDecisionRequest request) {
        return discrepancies.decide(user, orderId, request);
    }

    @Operation(summary = "The source Branch Manager records goods coming back from the destination (UC-76, BR-61). "
            + "Inspected and confirmed like any receipt under /receipts/{receiptId}")
    @PostMapping("/returns")
    @PreAuthorize("hasRole('BRANCH_MANAGER')")
    public ResponseEntity<TransferReceiptResponse> arriveReturn(@AuthenticationPrincipal AuthenticatedUser user,
                                                                @PathVariable long orderId,
                                                                @Valid @RequestBody TransferReturnRequest request) {
        TransferReceiptResponse created = receipts.arriveReturn(user, orderId, request);
        return ResponseEntity.created(URI.create("/api/v1/transfer-orders/" + orderId + "/receipts/" + created.id()))
                .body(created);
    }
}
