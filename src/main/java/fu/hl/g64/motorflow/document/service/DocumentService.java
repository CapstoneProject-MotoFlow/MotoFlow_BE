package fu.hl.g64.motorflow.document.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.document.dto.DocumentDetail;
import fu.hl.g64.motorflow.document.dto.DocumentPrint;
import fu.hl.g64.motorflow.document.dto.DocumentRow;
import fu.hl.g64.motorflow.document.dto.DocumentSearch;
import fu.hl.g64.motorflow.document.dto.DocumentType;
import fu.hl.g64.motorflow.document.dto.PrintForm;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/**
 * FE-10 document management: UC-78 search, UC-79 view and UC-80 print the posted documents of the store (Branch
 * Manager, BR-03) or of every store (Owner).
 */
public interface DocumentService {

    /** UC-78: newest first. */
    PageResponse<DocumentRow> search(AuthenticatedUser user, DocumentSearch search, int page, int size);

    /** UC-79. */
    DocumentDetail detail(AuthenticatedUser user, DocumentType type, long id);

    /** UC-80: {@code form} null takes the document's first form. */
    DocumentPrint print(AuthenticatedUser user, DocumentType type, long id, PrintForm form);
}
