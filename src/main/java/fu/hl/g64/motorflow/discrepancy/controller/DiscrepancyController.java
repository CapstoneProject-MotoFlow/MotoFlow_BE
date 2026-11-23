package fu.hl.g64.motorflow.discrepancy.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.discrepancy.dto.AcceptAsVariantRequest;
import fu.hl.g64.motorflow.discrepancy.dto.AcceptanceRequest;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancyCaseResponse;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancySearch;
import fu.hl.g64.motorflow.discrepancy.dto.ReceiveWrongItemsRequest;
import fu.hl.g64.motorflow.discrepancy.dto.RejectAcceptanceRequest;
import fu.hl.g64.motorflow.discrepancy.dto.SupplierReturnRequest;
import fu.hl.g64.motorflow.discrepancy.dto.WaiveReplacementRequest;
import fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus;
import fu.hl.g64.motorflow.discrepancy.service.DiscrepancyService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.receiving.service.GoodsReceiptService;
import fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

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
    private final GoodsReceiptService receipts;

    @GetMapping("/receipts/{receiptId}/handling")
    public ReceiptHandling handling(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long receiptId) {
        var receipt = receipts.get(user, receiptId);
        List<ExcessLine> excess = receipt.lines().stream().filter(line -> line.excessQty() > 0)
                .map(line -> new ExcessLine(line.id(), line.productId(), line.sku(), line.partName(),
                        line.excessQty(), line.excessDecision())).toList();
        return new ReceiptHandling(excess, receipt.status() == GoodsReceiptNoteStatus.CONFIRMED
                && receipt.openDiscrepancies() == 0
                && excess.stream().noneMatch(line -> line.decision() == ExcessDecision.AWAITING_OWNER));
    }

    public record ReceiptHandling(List<ExcessLine> excessLines, boolean handlingComplete) {
    }

    public record ExcessLine(long lineId, long productId, String sku, String partName, int quantity,
                             ExcessDecision decision) {
    }


    @Operation(summary = "Supplier discrepancy cases of every warehouse (Owner) or of the user's warehouse")
    @GetMapping
    public PageResponse<DiscrepancyCaseResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @RequestParam(required = false) DiscrepancyCaseStatus status,
                                                      @RequestParam(required = false) Long storeId,
                                                      @RequestParam(required = false) Long purchaseOrderId,
                                                      @RequestParam(required = false) Long goodsReceiptNoteId,
                                                      @RequestParam(required = false) Long supplierId,
                                                      @RequestParam(required = false)
                                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                      LocalDate deliveredFrom,
                                                      @RequestParam(required = false)
                                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                      LocalDate deliveredTo,
                                                      @RequestParam(required = false) String q,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return discrepancies.list(user, new DiscrepancySearch(status, storeId, purchaseOrderId, goodsReceiptNoteId,
                supplierId, deliveredFrom, deliveredTo, q), page, size);
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

    @Operation(summary = "Ask Owner to take held damaged or wrong goods into stock (GVHD 20/11)",
            description = "Branch Manager of the receiving store or Owner. type DAMAGED (as the \"-M\" variant) or "
                    + "WRONG_ITEM (productId: the part they really are, optional). MSG309: more than is held and not "
                    + "decided of that kind. Owner is notified; MSG314: that kind already has a request waiting. The case shows it in pendingRequests.")
    @PostMapping("/{id}/acceptance-requests")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'OWNER')")
    public DiscrepancyCaseResponse requestAcceptance(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @PathVariable long id,
                                                     @Valid @RequestBody AcceptanceRequest request) {
        return discrepancies.requestAcceptance(user, id, request);
    }

    @Operation(summary = "Refuse the pending request to take goods into stock (GVHD 20/11)",
            description = "Owner only. MSG29 when nothing is pending. The goods stay held; the store's managers are told.")
    @PostMapping("/{id}/acceptance-requests/reject")
    @PreAuthorize("hasRole('OWNER')")
    public DiscrepancyCaseResponse rejectAcceptance(@AuthenticationPrincipal AuthenticatedUser owner,
                                                    @PathVariable long id,
                                                    @Valid @RequestBody RejectAcceptanceRequest request) {
        return discrepancies.rejectAcceptance(owner, id, request);
    }

    @Operation(summary = "Take dented goods into stock as the part's dented-goods variant (GVHD 20/11)",
            description = "Owner only. The variant \"<SKU>-M\" is made the first time and shelved in \"Khu hàng lỗi\". "
                    + "salePrice sets its retail price from today (required while it has none: MSG311). MSG309, MSG312.")
    @PostMapping("/{id}/damaged/accept-as-variant")
    @PreAuthorize("hasRole('OWNER')")
    public DiscrepancyCaseResponse acceptAsVariant(@AuthenticationPrincipal AuthenticatedUser owner,
                                                   @PathVariable long id,
                                                   @Valid @RequestBody AcceptAsVariantRequest request) {
        return discrepancies.acceptAsVariant(owner, id, request);
    }

    @Operation(summary = "Take wrong items into stock as the part they really are (GVHD 20/11)",
            description = "Owner only. replacementNeeded false waives the replacement owed for them. MSG309, MSG310, "
                    + "MSG313.")
    @PostMapping("/{id}/wrong-items/receive")
    @PreAuthorize("hasRole('OWNER')")
    public DiscrepancyCaseResponse receiveWrongItems(@AuthenticationPrincipal AuthenticatedUser owner,
                                                     @PathVariable long id,
                                                     @Valid @RequestBody ReceiveWrongItemsRequest request) {
        return discrepancies.receiveWrongItems(owner, id, request);
    }
}
