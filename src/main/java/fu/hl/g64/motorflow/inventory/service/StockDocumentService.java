package fu.hl.g64.motorflow.inventory.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.inventory.dto.StockDocument;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef.SourceType;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/**
 * Q8, B10 (PM 23/11): stock documents. The Owner sees every warehouse with values; a Branch Manager or Staff only
 * their own warehouse (MSG05 for another), without values (BR-72).
 */
public interface StockDocumentService {

    /** One page, newest first. MSG02 when {@code from} is after {@code to}. */
    PageResponse<StockDocument.Summary> list(AuthenticatedUser user, Long storeId, StockDocument.Search search,
                                             int page, int size);

    /** One document with its lines. The Owner names the warehouse (MSG02 without it); MSG70 when there is none. */
    StockDocument.Detail detail(AuthenticatedUser user, SourceType type, long sourceId, Long storeId,
                                StockDocument.Direction direction);
}
