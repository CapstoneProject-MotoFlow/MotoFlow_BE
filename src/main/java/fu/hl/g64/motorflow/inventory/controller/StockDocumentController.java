package fu.hl.g64.motorflow.inventory.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.inventory.dto.StockDocument;
import fu.hl.g64.motorflow.inventory.dto.StockDocument.Direction;
import fu.hl.g64.motorflow.inventory.dto.StockDocument.Kind;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef.SourceType;
import fu.hl.g64.motorflow.inventory.service.StockDocumentService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "Stock documents", description = "Q8: stock-in and stock-out documents by warehouse (Owner, Branch Manager, Staff)")
@RestController
@RequestMapping("/api/v1/stock-documents")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER', 'STAFF')")
@RequiredArgsConstructor
public class StockDocumentController {

    private final StockDocumentService documents;

    @Operation(summary = "Stock documents, newest first: the Owner sees every warehouse (or storeId) with values, a "
            + "Branch Manager or Staff only their warehouse without values")
    @GetMapping
    public PageResponse<StockDocument.Summary> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                    @RequestParam(required = false) Long storeId,
                                                    @RequestParam(required = false) Direction direction,
                                                    @RequestParam(required = false) SourceType type,
                                                    @RequestParam(required = false) Kind kind,
                                                    @RequestParam(required = false) LocalDate from,
                                                    @RequestParam(required = false) LocalDate to,
                                                    @RequestParam(defaultValue = "") String q,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return documents.list(user, storeId, new StockDocument.Search(direction, type, kind, from, to, q), page, size);
    }

    @Operation(summary = "One stock document with its lines (part, lot, expiry, quantity; unit cost and value for the "
            + "Owner). The Owner passes storeId")
    @GetMapping("/{type}/{sourceId}")
    public StockDocument.Detail detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable SourceType type,
                                       @PathVariable long sourceId, @RequestParam(required = false) Long storeId,
                                       @RequestParam Direction direction) {
        return documents.detail(user, type, sourceId, storeId, direction);
    }
}
