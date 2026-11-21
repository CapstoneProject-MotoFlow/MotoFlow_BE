package fu.hl.g64.motorflow.sales.controller;

import fu.hl.g64.motorflow.sales.dto.DepositExpiryRequest;
import fu.hl.g64.motorflow.sales.dto.DepositPaymentRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail;
import fu.hl.g64.motorflow.sales.dto.SettlementResult;
import fu.hl.g64.motorflow.sales.service.SalesDepositService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Sales order deposit", description = "PM 21/11: a deposit on a waiting order (AWAITING_STOCK) of a "
        + "customer with a profile. It holds no goods; a verified deposit counts (verify or reject it with "
        + "/payments/{paymentId}/verify or /reject). Amounts are in VND.")
@RestController
@RequestMapping("/api/v1/sales-orders/{id}")
@RequiredArgsConstructor
public class SalesDepositController {

    private final SalesDepositService deposits;

    @Operation(summary = "Record a deposit, first or additional, on a waiting order",
            description = "Owner or the store's Branch Manager. Only an AWAITING_STOCK order (MSG319) with a customer "
                    + "profile (MSG320) whose deposit has not expired (MSG321). The first deposit sets "
                    + "depositExpiresOn, after today (MSG322); later ones leave it out or repeat it (MSG323). "
                    + "expectedDepositRecorded = deposit.total + deposit.unverified as shown (MSG194). Cash tendered "
                    + "and bank transfer references as for an order payment (MSG182, MSG154, MSG151).")
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
    @PostMapping("/deposits")
    public SettlementResult recordDeposit(@AuthenticationPrincipal AuthenticatedUser seller, @PathVariable long id,
                                          @Valid @RequestBody DepositPaymentRequest request) {
        return deposits.recordDeposit(seller, id, request);
    }

    @Operation(summary = "Change the expiry date of a deposit",
            description = "Only the store's Branch Manager (403 for Owner and Staff). The order still waits for "
                    + "stock (MSG319), has a deposit (MSG324) that has not expired (MSG321); the new date is after "
                    + "today (MSG322). No limit on the number of changes; each one is listed in "
                    + "deposit.expiryChanges.")
    @PreAuthorize("hasRole('BRANCH_MANAGER')")
    @PatchMapping("/deposit-expiry")
    public SalesOrderDetail changeExpiry(@AuthenticationPrincipal AuthenticatedUser manager, @PathVariable long id,
                                         @Valid @RequestBody DepositExpiryRequest request) {
        return deposits.changeExpiry(manager, id, request);
    }

    @Operation(summary = "Declare an expired deposit lost and cancel the waiting order",
            description = "Only the store's Branch Manager (403 for Owner and Staff). The order still waits for "
                    + "stock (MSG319) and its deposit has EXPIRED: MSG329 while it holds, MSG330 when no deposit is "
                    + "left. The order is cancelled with the reason \"Hết hạn cọc\"; nothing is refunded and "
                    + "deposit.forfeitedAmount is what the store keeps. POST /cancel of a waiting order keeps the "
                    + "deposit left unless its refund returns that amount (MSG331). An Owner cannot cancel an expired "
                    + "deposit without that refund.")
    @PreAuthorize("hasRole('BRANCH_MANAGER')")
    @PostMapping("/forfeit-deposit")
    public SalesOrderDetail forfeit(@AuthenticationPrincipal AuthenticatedUser manager, @PathVariable long id) {
        return deposits.forfeit(manager, id);
    }
}
