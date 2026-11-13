package fu.hl.g64.motorflow.sales.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail;
import fu.hl.g64.motorflow.sales.dto.SalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderSummary;
import fu.hl.g64.motorflow.sales.service.SalesOrderService;
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

@Tag(name = "Sales drafts", description = "PM 13/11: a draft is not a sales order until submitted. Only its creator "
        + "sees, edits, submits or deletes it; anyone else gets 404 MSG156. A draft holds no stock, has no picking "
        + "task or money, and appears in no sales order list, customer page, report or home count.")
@RestController
@RequestMapping("/api/v1/sales-orders/drafts")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
@RequiredArgsConstructor
public class SalesDraftController {

    private final SalesOrderService service;

    @Operation(summary = "The caller's drafts (draft, not a sales order until submitted), newest first. q matches the "
            + "number, customer name or phone; storeId filters for Owner")
    @GetMapping
    public PageResponse<SalesOrderSummary> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                @RequestParam(required = false) Long storeId,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return service.drafts(user, storeId, q, page, size);
    }

    @Operation(summary = "Save a draft (draft, not a sales order until submitted). Checked as UC-53 except the "
            + "discount threshold (BR-16)")
    @PostMapping
    public ResponseEntity<SalesOrderDetail> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @Valid @RequestBody SalesOrderRequest request) {
        SalesOrderDetail created = service.createDraft(user, request);
        return ResponseEntity.created(URI.create("/api/v1/sales-orders/drafts/" + created.id())).body(created);
    }

    @Operation(summary = "One of the caller's drafts (draft, not a sales order until submitted)")
    @GetMapping("/{id}")
    public SalesOrderDetail get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return service.draft(user, id);
    }

    @Operation(summary = "Replace the whole draft (draft, not a sales order until submitted): store, customer, price "
            + "type, discount, note and lines")
    @PutMapping("/{id}")
    public SalesOrderDetail update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                   @Valid @RequestBody SalesOrderRequest request) {
        return service.updateDraft(user, id, request);
    }

    @Operation(summary = "\"Tạo đơn\": save the content sent and submit the draft as a sales order waiting for "
            + "picking, checked exactly as POST /sales-orders, with the same id. A refusal leaves the draft as it was")
    @PostMapping("/{id}/submit")
    public SalesOrderDetail submit(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                   @Valid @RequestBody SalesOrderRequest request) {
        return service.submitDraft(user, id, request);
    }

    @Operation(summary = "Delete the draft and its lines for good (draft, not a sales order until submitted)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        service.deleteDraft(user, id);
        return ResponseEntity.noContent().build();
    }
}
