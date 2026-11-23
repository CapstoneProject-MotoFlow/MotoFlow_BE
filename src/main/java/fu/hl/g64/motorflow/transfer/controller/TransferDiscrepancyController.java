package fu.hl.g64.motorflow.transfer.controller;

import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.ReceiverMiscountRequest;
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

    @Operation(summary = "PM 23/11 case 1: the receiving store's Branch Manager or the Owner decides a short part comes "
            + "later (LATE_DELIVERY) or is not to be replaced (NO_REPLACEMENT, back to the source's stock on closing). "
            + "What nobody decides on is lost on closing. RETURN_TO_SOURCE and LOSS are refused (MSG345)")
    @PostMapping("/discrepancy/decisions")
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
    public TransferOrderDetailResponse decide(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long orderId,
                                              @Valid @RequestBody TransferDecisionRequest request) {
        return discrepancies.decide(user, orderId, request);
    }

    @Operation(summary = "PM 23/11 case 7, Owner: the receiving store counted the line wrong. The destination gets "
            + "what was dispatched, the source is unchanged, the line's excess, wrong items and decisions are void; the "
            + "staff's receipt keeps its figures. Reason required (MSG343)")
    @PostMapping("/discrepancies/{caseId}/receiver-miscount")
    @PreAuthorize("hasRole('OWNER')")
    public TransferOrderDetailResponse receiverMiscount(@AuthenticationPrincipal AuthenticatedUser user,
                                                        @PathVariable long orderId, @PathVariable long caseId,
                                                        @Valid @RequestBody ReceiverMiscountRequest request) {
        return discrepancies.receiverMiscount(user, orderId, caseId, request);
    }

    @Operation(summary = "Orders before PM 23/11 only: the source Branch Manager records goods coming back from the "
            + "destination for an earlier return decision (BR-61); without one refused (MSG345)")
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
