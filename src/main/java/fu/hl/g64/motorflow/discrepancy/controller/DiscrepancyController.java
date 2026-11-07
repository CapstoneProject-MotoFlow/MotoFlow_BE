package fu.hl.g64.motorflow.discrepancy.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancyCaseResponse;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancySearch;
import fu.hl.g64.motorflow.discrepancy.dto.SupplierReturnRequest;
import fu.hl.g64.motorflow.discrepancy.dto.WaiveReplacementRequest;
import fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus;
import fu.hl.g64.motorflow.discrepancy.service.DiscrepancyService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
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

@Tag(name = "Supplier discrepancies", description = "UC-46: short, wrong-item, damaged and rejected excess goods of "
        + "supplier deliveries, tracked until they are returned and replaced. The handling of a delivery's cases is "
        + "saved on the GRN (PUT /api/v1/goods-receipt-notes/{id}/discrepancies); a replacement delivery is a GRN "
        + "created from the PO with replacementForCaseIds.")
@RestController
@RequestMapping("/api/v1/discrepancy-cases")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
@RequiredArgsConstructor
public class DiscrepancyController {

    private final DiscrepancyService discrepancies;

    @Operation(summary = "Supplier discrepancy cases of every warehouse (Owner) or of the user's warehouse")
    @GetMapping
    public PageResponse<DiscrepancyCaseResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @RequestParam(required = false) DiscrepancyCaseStatus status,
                                                      @RequestParam(required = false) Long storeId,
                                                      @RequestParam(required = false) Long purchaseOrderId,
                                                      @RequestParam(required = false) Long goodsReceiptNoteId,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return discrepancies.list(user, new DiscrepancySearch(status, storeId, purchaseOrderId, goodsReceiptNoteId),
                page, size);
    }

    @Operation(summary = "A discrepancy case with what was done on it (UC-46 step 2)")
    @GetMapping("/{id}")
    public DiscrepancyCaseResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return discrepancies.get(user, id);
    }

    @Operation(summary = "Record goods handed back to the supplier (UC-46 actual supplier return)")
    @PostMapping("/{id}/supplier-returns")
    @PreAuthorize("hasRole('BRANCH_MANAGER')")
    public DiscrepancyCaseResponse recordSupplierReturn(@AuthenticationPrincipal AuthenticatedUser manager,
                                                        @PathVariable long id,
                                                        @Valid @RequestBody SupplierReturnRequest request) {
        return discrepancies.recordSupplierReturn(manager, id, request);
    }

    @Operation(summary = "Waive replacement agreed with the supplier (UC-46 shortage settled without replacement)")
    @PostMapping("/{id}/waive-replacement")
    @PreAuthorize("hasRole('OWNER')")
    public DiscrepancyCaseResponse waiveReplacement(@AuthenticationPrincipal AuthenticatedUser owner,
                                                    @PathVariable long id,
                                                    @Valid @RequestBody WaiveReplacementRequest request) {
        return discrepancies.waiveReplacement(owner, id, request);
    }
}
