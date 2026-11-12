package fu.hl.g64.motorflow.transfer.controller;

import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.RecountRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferReceiptRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferReceiptResponse;
import fu.hl.g64.motorflow.transfer.dto.TransferReceiptResultRequest;
import fu.hl.g64.motorflow.transfer.service.TransferReceiptService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Tag(name = "Transfer receipts", description = "UC-106, UC-43, UC-44 for transferred goods")
@RestController
@RequestMapping("/api/v1/transfer-orders/{orderId}/receipts")
@RequiredArgsConstructor
public class TransferReceiptController {

    private final TransferReceiptService service;

    @Operation(summary = "Whoever holds the TRANSFER_RECEIVING task records that the goods arrived with their delivery document. Each part expects what the "
            + "order has not received, returned or written off yet. The inspection goes to the same person")
    @PostMapping
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'STAFF')")
    public ResponseEntity<TransferReceiptResponse> arrive(@AuthenticationPrincipal AuthenticatedUser user,
                                                          @PathVariable long orderId,
                                                          @Valid @RequestBody TransferReceiptRequest request) {
        TransferReceiptResponse created = service.arrive(user, orderId, request);
        return ResponseEntity.created(URI.create("/api/v1/transfer-orders/" + orderId + "/receipts/" + created.id()))
                .body(created);
    }

    @Operation(summary = "One receipt of the order: receiving Branch Manager, coordinators, the inspecting Staff")
    @GetMapping("/{receiptId}")
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER', 'STAFF')")
    public TransferReceiptResponse receipt(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long orderId,
                                           @PathVariable long receiptId) {
        return service.receipt(user, orderId, receiptId);
    }

    @Operation(summary = "The assigned Staff member records passed and damaged per part (UC-43). Above what was "
            + "expected is excess, which stays out of stock until the order is closed (BR-68)")
    @PutMapping("/{receiptId}/result")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'STAFF')")
    public TransferReceiptResponse recordResult(@AuthenticationPrincipal AuthenticatedUser user,
                                                @PathVariable long orderId, @PathVariable long receiptId,
                                                @Valid @RequestBody TransferReceiptResultRequest request) {
        return service.recordResult(user, orderId, receiptId, request);
    }

    @Operation(summary = "Send the result back for a recount (UC-44). Receiving Branch Manager; posts nothing")
    @PostMapping("/{receiptId}/recount")
    @PreAuthorize("hasRole('BRANCH_MANAGER')")
    public TransferReceiptResponse returnForRecount(@AuthenticationPrincipal AuthenticatedUser user,
                                                    @PathVariable long orderId, @PathVariable long receiptId,
                                                    @Valid @RequestBody RecountRequest request) {
        return service.returnForRecount(user, orderId, receiptId, request);
    }

    @Operation(summary = "Confirm the receipt (UC-44). A receiving Branch Manager who did not record it; moves the "
            + "order's own lots out of transit and opens a discrepancy case for what is missing")
    @PostMapping("/{receiptId}/confirm")
    @PreAuthorize("hasRole('BRANCH_MANAGER')")
    public TransferReceiptResponse confirm(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long orderId,
                                           @PathVariable long receiptId) {
        return service.confirm(user, orderId, receiptId);
    }
}
