package fu.hl.g64.motorflow.document.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.document.dto.DocumentDetail;
import fu.hl.g64.motorflow.document.dto.DocumentPrint;
import fu.hl.g64.motorflow.document.dto.DocumentRow;
import fu.hl.g64.motorflow.document.dto.DocumentSearch;
import fu.hl.g64.motorflow.document.dto.DocumentType;
import fu.hl.g64.motorflow.document.dto.PrintForm;
import fu.hl.g64.motorflow.document.service.DocumentService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "Documents", description = "FE-10 posted documents of every module (screen 26): UC-78 search, UC-79 view "
        + "with source documents, adjustments and history, UC-80 print data. Branch Manager: own store (BR-03); Owner: "
        + "every store.")
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
public class DocumentController {

    private final DocumentService documents;

    @Operation(summary = "Search posted documents, newest first (UC-78)",
            description = "number: \"#15\" or \"15\". from/to: document date, both days included. storeId: Owner only; "
                    + "a Branch Manager asking for another store gets MSG05.")
    @GetMapping
    public PageResponse<DocumentRow> search(@AuthenticationPrincipal AuthenticatedUser user,
                                            @RequestParam(required = false) DocumentType type,
                                            @RequestParam(required = false) String number,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(required = false) Long storeId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return documents.search(user, new DocumentSearch(type, number, from, to, storeId), page, size);
    }

    @Operation(summary = "A document with its source documents, related records, adjustments and history (UC-79)")
    @GetMapping("/{type}/{id}")
    public DocumentDetail detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable DocumentType type,
                                 @PathVariable long id) {
        return documents.detail(user, type, id);
    }

    @Operation(summary = "What a printed form of the document shows (UC-80)",
            description = "form: one of the document's printForms, the first when left out; MSG252 otherwise.")
    @GetMapping("/{type}/{id}/print")
    public DocumentPrint print(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable DocumentType type,
                               @PathVariable long id, @RequestParam(required = false) PrintForm form) {
        return documents.print(user, type, id, form);
    }
}
