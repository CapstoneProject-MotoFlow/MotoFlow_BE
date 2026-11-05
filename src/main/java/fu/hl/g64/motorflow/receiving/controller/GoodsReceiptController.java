package fu.hl.g64.motorflow.receiving.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancyCaseResponse;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancyHandlingRequest;
import fu.hl.g64.motorflow.discrepancy.service.DiscrepancyService;
import fu.hl.g64.motorflow.receiving.dto.AssignedReceiptResponse;
import fu.hl.g64.motorflow.receiving.dto.ExcessDecisionRequest;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptDetail;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptSearch;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptSummary;
import fu.hl.g64.motorflow.receiving.dto.InspectionResultRequest;
import fu.hl.g64.motorflow.receiving.dto.RecountRequest;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.service.GoodsReceiptService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Goods receipt notes", description = "Supplier deliveries received at a warehouse (FE-05): UC-43 record "
        + "the inspection, UC-44 confirm it or return it for a recount, UC-45 decide excess, UC-46 discrepancy handling. "
        + "A GRN is created from its purchase order (UC-106).")
@RestController
@RequestMapping("/api/v1/goods-receipt-notes")
@RequiredArgsConstructor
public class GoodsReceiptController {

    private final GoodsReceiptService goodsReceipts;
    private final DiscrepancyService discrepancies;

    @Operation(summary = "Supplier deliveries whose inspection the user holds and has not finished (UC-39, Staff)")
    @GetMapping("/assigned-to-me")
    @PreAuthorize("hasAnyRole('STAFF', 'BRANCH_MANAGER')")
    public PageResponse<AssignedReceiptResponse> assignedToMe(@AuthenticationPrincipal AuthenticatedUser user,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        return goodsReceipts.assignedToMe(user, page, size);
    }

    @Operation(summary = "Supplier deliveries of every warehouse (Owner) or of the user's warehouse",
            description = "excessAwaiting=true: only receipts with excess Owner has not decided on. unassigned=true: "
                    + "only receipts waiting for inspection that nobody holds.")
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
    public PageResponse<GoodsReceiptSummary> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @RequestParam(required = false) Long storeId,
                                                  @RequestParam(required = false) Long purchaseOrderId,
                                                  @RequestParam(required = false) GoodsReceiptNoteStatus status,
                                                  @RequestParam(required = false) Boolean excessAwaiting,
                                                  @RequestParam(required = false) Boolean unassigned,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return goodsReceipts.list(user, new GoodsReceiptSearch(storeId, purchaseOrderId, status, excessAwaiting,
                unassigned), page, size);
    }

    @Operation(summary = "A supplier delivery with its expected parts, inspection result and documents")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER', 'STAFF')")
    public GoodsReceiptDetail get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return goodsReceipts.get(user, id);
    }

    @Operation(summary = "Send the inspection result for confirmation (UC-43)",
            description = "Only the person holding the inspection (UC-86). A line left out did not arrive.")
    @PostMapping("/{id}/result")
    @PreAuthorize("hasAnyRole('STAFF', 'BRANCH_MANAGER')")
    public GoodsReceiptDetail recordResult(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                           @Valid @RequestBody InspectionResultRequest request) {
        return goodsReceipts.recordResult(user, id, request);
    }

    @Operation(summary = "Confirm the inspection result and post the passed goods to stock (UC-44)")
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('BRANCH_MANAGER')")
    public GoodsReceiptDetail confirm(@AuthenticationPrincipal AuthenticatedUser manager, @PathVariable long id) {
        return goodsReceipts.confirm(manager, id);
    }

    @Operation(summary = "Return the inspection result to the person who recorded it (UC-44 recount)")
    @PostMapping("/{id}/recount")
    @PreAuthorize("hasRole('BRANCH_MANAGER')")
    public GoodsReceiptDetail returnForRecount(@AuthenticationPrincipal AuthenticatedUser manager,
                                               @PathVariable long id, @Valid @RequestBody RecountRequest request) {
        return goodsReceipts.returnForRecount(manager, id, request.reason());
    }

    @Operation(summary = "Accept or reject the excess of a confirmed delivery (UC-45)")
    @PostMapping("/{id}/excess-decision")
    @PreAuthorize("hasRole('OWNER')")
    public GoodsReceiptDetail decideExcess(@AuthenticationPrincipal AuthenticatedUser owner, @PathVariable long id,
                                           @Valid @RequestBody ExcessDecisionRequest request) {
        return goodsReceipts.decideExcess(owner, id, request);
    }

    @Operation(summary = "Save the handling agreed with the supplier for the delivery's discrepancies (UC-46)",
            description = "Sets the quantity to return for each case; those goods move to awaiting return to "
                    + "supplier. Returns every case of the delivery.")
    @PutMapping("/{id}/discrepancies")
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
    public List<DiscrepancyCaseResponse> saveHandling(@AuthenticationPrincipal AuthenticatedUser handler,
                                                      @PathVariable long id,
                                                      @Valid @RequestBody DiscrepancyHandlingRequest request) {
        return discrepancies.saveHandling(handler, id, request);
    }
}
