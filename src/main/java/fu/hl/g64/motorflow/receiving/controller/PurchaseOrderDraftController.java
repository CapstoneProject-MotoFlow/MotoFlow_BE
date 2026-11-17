package fu.hl.g64.motorflow.receiving.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderDetail;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderRequest;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderSummary;
import fu.hl.g64.motorflow.receiving.service.PurchaseOrderService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Tag(name = "Purchase order drafts", description = "PM 17/11: a draft is not a purchase order until submitted. Only "
        + "its creator sees, edits, submits or deletes it; anyone else gets 404 MSG131. A draft has no receiving task "
        + "or notification, cannot be linked to a replenishment request, and appears in no PO list, supplier page, "
        + "document search or report. The POs' own endpoints answer 404 MSG131 for a draft.")
@RestController
@RequestMapping("/api/v1/purchase-orders/drafts")
@PreAuthorize("hasRole('OWNER')")
@RequiredArgsConstructor
public class PurchaseOrderDraftController {

    private final PurchaseOrderService service;

    @Operation(summary = "The caller's drafts, last saved first (createdAt is when it was last saved). q: the number "
            + "(12, #12, DNH-0012) or part of the supplier's name or code")
    @GetMapping
    public PageResponse<PurchaseOrderSummary> list(@AuthenticationPrincipal AuthenticatedUser owner,
                                                   @RequestParam(required = false) String q,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return service.drafts(owner, q, page, size);
    }

    @Operation(summary = "Save a draft, checked as POST /purchase-orders (UC-40); replenishmentRequestId must be "
            + "left out (MSG02)")
    @PostMapping
    public ResponseEntity<PurchaseOrderDetail> create(@AuthenticationPrincipal AuthenticatedUser owner,
                                                      @Valid @RequestBody PurchaseOrderRequest request) {
        PurchaseOrderDetail created = service.createDraft(owner, request);
        return ResponseEntity.created(URI.create("/api/v1/purchase-orders/drafts/" + created.id())).body(created);
    }

    @Operation(summary = "One of the caller's drafts")
    @GetMapping("/{id}")
    public PurchaseOrderDetail get(@AuthenticationPrincipal AuthenticatedUser owner, @PathVariable long id) {
        return service.draft(owner, id);
    }

    @Operation(summary = "Replace the whole draft: supplier, warehouse, expected arrival, note and lines")
    @PutMapping("/{id}")
    public PurchaseOrderDetail update(@AuthenticationPrincipal AuthenticatedUser owner, @PathVariable long id,
                                      @Valid @RequestBody PurchaseOrderRequest request) {
        return service.updateDraft(owner, id, request);
    }

    @Operation(summary = "\"Tạo đơn\": save the content sent and place the draft as a PO waiting for delivery, "
            + "checked exactly as POST /purchase-orders, with the same id; the warehouse is notified. A refusal "
            + "leaves the draft as it was")
    @PostMapping("/{id}/submit")
    public PurchaseOrderDetail submit(@AuthenticationPrincipal AuthenticatedUser owner, @PathVariable long id,
                                      @Valid @RequestBody PurchaseOrderRequest request) {
        return service.submitDraft(owner, id, request);
    }

    @Operation(summary = "Delete the draft and its lines for good")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser owner, @PathVariable long id) {
        service.deleteDraft(owner, id);
        return ResponseEntity.noContent().build();
    }
}
