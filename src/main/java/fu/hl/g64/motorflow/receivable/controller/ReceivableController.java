package fu.hl.g64.motorflow.receivable.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.receivable.dto.DebtorResponse;
import fu.hl.g64.motorflow.receivable.service.CustomerDebtService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Receivables", description = "UC-64 customer debt list (Owner, Branch Manager). Amounts are in VND.")
@RestController
@RequestMapping("/api/v1/receivables")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
@RequiredArgsConstructor
public class ReceivableController {

    private final CustomerDebtService debts;

    @Operation(summary = "Customers who still owe the store, or any store for Owner, overdue first (UC-64)")
    @GetMapping("/customers")
    public PageResponse<DebtorResponse> debtors(@AuthenticationPrincipal AuthenticatedUser user,
                                                @RequestParam(required = false) Long storeId,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(defaultValue = "false") boolean overdueOnly,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return debts.debtors(user, storeId, q, overdueOnly, page, size);
    }
}
