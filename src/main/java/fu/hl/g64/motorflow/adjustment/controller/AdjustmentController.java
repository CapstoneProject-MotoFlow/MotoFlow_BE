package fu.hl.g64.motorflow.adjustment.controller;

import fu.hl.g64.motorflow.adjustment.dto.AdjustmentDetail;
import fu.hl.g64.motorflow.adjustment.dto.AdjustmentPreview;
import fu.hl.g64.motorflow.adjustment.dto.AdjustmentSearch;
import fu.hl.g64.motorflow.adjustment.dto.AdjustmentSummary;
import fu.hl.g64.motorflow.adjustment.dto.RejectAdjustmentRequest;
import fu.hl.g64.motorflow.adjustment.dto.StockLossRequest;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentType;
import fu.hl.g64.motorflow.adjustment.service.AdjustmentService;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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

import java.time.LocalDate;

@Tag(name = "Adjustment requests", description = "FE-10: stock loss or damage (UC-83) and corrections of posted "
        + "documents (UC-81) reported by a Branch Manager or Owner, listed (UC-82) and decided by Owner (UC-84). "
        + "Nothing changes stock or debt until Owner approves and the request is applied.")
@RestController
@RequestMapping("/api/v1/adjustment-requests")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
@RequiredArgsConstructor
public class AdjustmentController {

    private final AdjustmentService adjustments;

    @Operation(summary = "Preview a stock loss or damage write-off; nothing is saved (UC-83 steps 2-5)")
    @PostMapping("/stock-loss/preview")
    public AdjustmentPreview previewStockLoss(@AuthenticationPrincipal AuthenticatedUser user,
                                              @Valid @RequestBody StockLossRequest request) {
        return adjustments.previewStockLoss(user, request);
    }

    @Operation(summary = "Submit a stock loss or damage write-off for Owner's approval (UC-83 steps 6-7)")
    @PostMapping("/stock-loss")
    @ResponseStatus(HttpStatus.CREATED)
    public AdjustmentDetail submitStockLoss(@AuthenticationPrincipal AuthenticatedUser user,
                                            @Valid @RequestBody StockLossRequest request) {
        return adjustments.submitStockLoss(user, request);
    }

    @Operation(summary = "Adjustment requests of the user's store, or of every store for Owner (UC-82)")
    @GetMapping
    public PageResponse<AdjustmentSummary> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                @RequestParam(required = false) AdjustmentType type,
                                                @RequestParam(required = false) AdjustmentRequestStatus status,
                                                @RequestParam(required = false) Long storeId,
                                                @RequestParam(required = false)
                                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                @RequestParam(required = false)
                                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                @RequestParam(defaultValue = "false") boolean mine,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return adjustments.list(user, new AdjustmentSearch(type, status, storeId, from, to, mine), page, size);
    }

    @Operation(summary = "A request with its evidence, proposed effects and decision history (UC-84 step 2)")
    @GetMapping("/{id}")
    public AdjustmentDetail get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return adjustments.get(user, id);
    }

    @Operation(summary = "Approve and apply on today's date (UC-84 steps 3-9, Owner). If applying fails the request "
            + "stays APPROVED and the error is returned; POST /{id}/apply tries again")
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('OWNER')")
    public AdjustmentDetail approve(@AuthenticationPrincipal AuthenticatedUser owner, @PathVariable long id) {
        return adjustments.approve(owner, id);
    }

    @Operation(summary = "Apply an approved request again (UC-84 Step 1_Approved but unapplied, Owner)")
    @PostMapping("/{id}/apply")
    @PreAuthorize("hasRole('OWNER')")
    public AdjustmentDetail apply(@AuthenticationPrincipal AuthenticatedUser owner, @PathVariable long id) {
        return adjustments.apply(owner, id);
    }

    @Operation(summary = "Reject with a reason; the requester is told (UC-84 Step 3_Reject request, Owner)")
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('OWNER')")
    public AdjustmentDetail reject(@AuthenticationPrincipal AuthenticatedUser owner, @PathVariable long id,
                                   @Valid @RequestBody RejectAdjustmentRequest request) {
        return adjustments.reject(owner, id, request);
    }

    @Operation(summary = "The requester takes back a request still waiting for Owner")
    @PostMapping("/{id}/withdraw")
    public AdjustmentDetail withdraw(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return adjustments.withdraw(user, id);
    }
}
