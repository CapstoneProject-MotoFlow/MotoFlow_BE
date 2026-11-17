package fu.hl.g64.motorflow.transfer.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentCreateRequest;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentDetailResponse;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentRowResponse;
import fu.hl.g64.motorflow.transfer.service.ReplenishmentRequestService;
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

@Tag(name = "Replenishment drafts", description = "PM 17/11: a draft is not a replenishment request until submitted. "
        + "Only its creator sees, edits, submits or deletes it; anyone else gets 404 MSG95. A draft tells nobody and "
        + "appears in no request list, coordination screen, home count or document search.")
@RestController
@RequestMapping("/api/v1/replenishment-requests/drafts")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
@RequiredArgsConstructor
public class ReplenishmentDraftController {

    private final ReplenishmentRequestService service;

    @Operation(summary = "The caller's drafts (draft, not a request until submitted), last saved first. q matches the "
            + "code (YBS-xxxx or id) or store name; storeId filters for Owner")
    @GetMapping
    public PageResponse<ReplenishmentRowResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                       @RequestParam(required = false) Long storeId,
                                                       @RequestParam(required = false) String q,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return service.drafts(user, storeId, q, page, size);
    }

    @Operation(summary = "Save a draft (draft, not a request until submitted). Checked as UC-68; nobody is told")
    @PostMapping
    public ResponseEntity<ReplenishmentDetailResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                              @Valid @RequestBody ReplenishmentCreateRequest request) {
        ReplenishmentDetailResponse created = service.createDraft(user, request);
        return ResponseEntity.created(URI.create("/api/v1/replenishment-requests/drafts/" + created.id()))
                .body(created);
    }

    @Operation(summary = "One of the caller's drafts (draft, not a request until submitted)")
    @GetMapping("/{id}")
    public ReplenishmentDetailResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return service.draft(user, id);
    }

    @Operation(summary = "Replace the whole draft (draft, not a request until submitted): store, note and lines")
    @PutMapping("/{id}")
    public ReplenishmentDetailResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                              @Valid @RequestBody ReplenishmentCreateRequest request) {
        return service.updateDraft(user, id, request);
    }

    @Operation(summary = "\"Gửi yêu cầu\": save the content sent and submit the draft as a request awaiting "
            + "coordination, checked and told exactly as POST /replenishment-requests, with the same id. A refusal "
            + "leaves the draft as it was")
    @PostMapping("/{id}/submit")
    public ReplenishmentDetailResponse submit(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                              @Valid @RequestBody ReplenishmentCreateRequest request) {
        return service.submitDraft(user, id, request);
    }

    @Operation(summary = "Delete the draft and its lines for good (draft, not a request until submitted)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        service.deleteDraft(user, id);
        return ResponseEntity.noContent().build();
    }
}
