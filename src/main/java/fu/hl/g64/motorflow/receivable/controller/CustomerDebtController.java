package fu.hl.g64.motorflow.receivable.controller;

import fu.hl.g64.motorflow.receivable.dto.CreditDecisionRequest;
import fu.hl.g64.motorflow.receivable.dto.CustomerCreditResponse;
import fu.hl.g64.motorflow.receivable.dto.CustomerDebtsResponse;
import fu.hl.g64.motorflow.receivable.dto.DebtPaymentPreviewRequest;
import fu.hl.g64.motorflow.receivable.dto.DebtPaymentPreviewResponse;
import fu.hl.g64.motorflow.receivable.dto.DebtPaymentRequest;
import fu.hl.g64.motorflow.receivable.dto.DebtPaymentResponse;
import fu.hl.g64.motorflow.receivable.dto.DebtResponse;
import fu.hl.g64.motorflow.receivable.dto.OpeningDebtRequest;
import fu.hl.g64.motorflow.receivable.service.CustomerCreditService;
import fu.hl.g64.motorflow.receivable.service.CustomerDebtService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Tag(name = "Customer credit and debts", description = "UC-62, UC-63 credit eligibility (Owner); UC-65 debt detail, "
        + "UC-66 debt payments (Owner, Branch Manager); UC-67 opening debts (Owner). Amounts are in VND.")
@RestController
@RequestMapping("/api/v1/customers/{customerId}")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
@RequiredArgsConstructor
public class CustomerDebtController {

    private final CustomerCreditService credit;
    private final CustomerDebtService debts;

    @Operation(summary = "Let the customer buy on credit until revoked (UC-62); already eligible changes nothing")
    @PostMapping("/credit/grant")
    @PreAuthorize("hasRole('OWNER')")
    public CustomerCreditResponse grantCredit(@AuthenticationPrincipal AuthenticatedUser owner,
                                              @PathVariable long customerId,
                                              @Valid @RequestBody CreditDecisionRequest request) {
        return credit.grant(owner, customerId, request);
    }

    @Operation(summary = "Stop credit sales to the customer; existing debts stay collectible (UC-63)")
    @PostMapping("/credit/revoke")
    @PreAuthorize("hasRole('OWNER')")
    public CustomerCreditResponse revokeCredit(@AuthenticationPrincipal AuthenticatedUser owner,
                                               @PathVariable long customerId,
                                               @Valid @RequestBody CreditDecisionRequest request) {
        return credit.revoke(owner, customerId, request);
    }

    @Operation(summary = "Every debt of the customer at the store, or at every store for Owner, with the debt payments "
            + "made on them (UC-65)")
    @GetMapping("/debts")
    public CustomerDebtsResponse debts(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long customerId,
                                       @RequestParam(required = false) Long storeId) {
        return debts.debts(user, customerId, storeId);
    }

    @Operation(summary = "Record a debt from before MotoFlow for one store, with its due date and evidence (UC-67)")
    @PostMapping("/opening-debts")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<DebtResponse> recordOpeningDebt(@AuthenticationPrincipal AuthenticatedUser owner,
                                                          @PathVariable long customerId,
                                                          @Valid @RequestBody OpeningDebtRequest request) {
        DebtResponse created = debts.recordOpeningDebt(owner, customerId, request);
        return ResponseEntity.created(URI.create("/api/v1/customers/" + customerId + "/debts?storeId=" + created.storeId()))
                .body(created);
    }

    @Operation(summary = "How a debt payment would be allocated: opening debts first, then the oldest credit sales "
            + "(UC-66 step 4, BR-70); nothing is saved")
    @PostMapping("/debt-payments/preview")
    public DebtPaymentPreviewResponse previewPayment(@AuthenticationPrincipal AuthenticatedUser collector,
                                                     @PathVariable long customerId,
                                                     @Valid @RequestBody DebtPaymentPreviewRequest request) {
        return debts.previewPayment(collector, customerId, request);
    }

    @Operation(summary = "Record one verified debt payment with the allocation confirmed from the preview (UC-66)")
    @PostMapping("/debt-payments")
    @ResponseStatus(HttpStatus.CREATED)
    public DebtPaymentResponse recordPayment(@AuthenticationPrincipal AuthenticatedUser collector,
                                             @PathVariable long customerId,
                                             @Valid @RequestBody DebtPaymentRequest request) {
        return debts.recordPayment(collector, customerId, request);
    }
}
