package fu.hl.g64.motorflow.sales.controller;

import fu.hl.g64.motorflow.sales.dto.CancelSalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.CompleteSalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.OrderCreditRequest;
import fu.hl.g64.motorflow.sales.dto.OrderPaymentRequest;
import fu.hl.g64.motorflow.sales.dto.OrderRefundRequest;
import fu.hl.g64.motorflow.sales.dto.PaymentRejectionRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail;
import fu.hl.g64.motorflow.sales.dto.SettlementResult;
import fu.hl.g64.motorflow.sales.service.SalesOrderSettlementService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Sales order payment, credit and cancellation", description = "UC-60 record order payment and complete, "
        + "UC-61 record order credit and complete, UC-56 cancel. Sellers only: Owner and the store's Branch Manager. "
        + "Completion deducts the delivered goods by lot and is done once; amounts are in VND.")
@RestController
@RequestMapping("/api/v1/sales-orders/{id}")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
@RequiredArgsConstructor
public class SalesOrderSettlementController {

    private final SalesOrderSettlementService settlement;

    @Operation(summary = "Record a payment of a confirmed order; a verified payment that pays the order in full "
            + "completes it (UC-60)")
    @PostMapping("/payments")
    public SettlementResult recordPayment(@AuthenticationPrincipal AuthenticatedUser seller, @PathVariable long id,
                                          @Valid @RequestBody OrderPaymentRequest request) {
        return settlement.recordPayment(seller, id, request);
    }

    @Operation(summary = "Give money back before completion, at most what the order holds (UC-60, BR-48)")
    @PostMapping("/refunds")
    public SettlementResult recordRefund(@AuthenticationPrincipal AuthenticatedUser seller, @PathVariable long id,
                                         @Valid @RequestBody OrderRefundRequest request) {
        return settlement.recordRefund(seller, id, request);
    }

    @Operation(summary = "Verify a payment or refund recorded unverified; it counts from now on (BR-46)")
    @PostMapping("/payments/{paymentId}/verify")
    public SettlementResult verifyPayment(@AuthenticationPrincipal AuthenticatedUser seller, @PathVariable long id,
                                          @PathVariable long paymentId) {
        return settlement.verifyPayment(seller, id, paymentId);
    }

    @Operation(summary = "Reject a payment or refund recorded unverified; it never counts (UC-56)")
    @PostMapping("/payments/{paymentId}/reject")
    public SalesOrderDetail rejectPayment(@AuthenticationPrincipal AuthenticatedUser seller, @PathVariable long id,
                                          @PathVariable long paymentId,
                                          @Valid @RequestBody PaymentRejectionRequest request) {
        return settlement.rejectPayment(seller, id, paymentId, request.reason());
    }

    @Operation(summary = "Complete a fully paid order whose completion failed earlier (UC-60 Step 2)")
    @PostMapping("/complete")
    public SalesOrderDetail complete(@AuthenticationPrincipal AuthenticatedUser seller, @PathVariable long id,
                                     @Valid @RequestBody CompleteSalesOrderRequest request) {
        return settlement.complete(seller, id, request);
    }

    @Operation(summary = "Put the unpaid balance on credit with its due date and complete the order (UC-61)")
    @PostMapping("/credit")
    public SalesOrderDetail recordCredit(@AuthenticationPrincipal AuthenticatedUser seller, @PathVariable long id,
                                         @Valid @RequestBody OrderCreditRequest request) {
        return settlement.recordCredit(seller, id, request);
    }

    @Operation(summary = "Refund what the order holds and cancel it (UC-56, BR-39)")
    @PostMapping("/cancel")
    public SalesOrderDetail cancel(@AuthenticationPrincipal AuthenticatedUser seller, @PathVariable long id,
                                   @Valid @RequestBody CancelSalesOrderRequest request) {
        return settlement.cancel(seller, id, request);
    }
}
