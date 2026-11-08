package fu.hl.g64.motorflow.catalog.service;

import fu.hl.g64.motorflow.catalog.dto.BarcodeLookupResponse;
import fu.hl.g64.motorflow.catalog.dto.ProductRequest;
import fu.hl.g64.motorflow.catalog.dto.ProductResponse;
import fu.hl.g64.motorflow.catalog.dto.ProductSearch;
import fu.hl.g64.motorflow.catalog.dto.ProductSummaryResponse;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/** UC-13 Search, UC-14 View, UC-15 Create, UC-16 Update and UC-17 Discontinue Spare Part. */
public interface ProductService {
    /** By SKU. Stock and storage location are those of the viewed store (BR-03). */
    PageResponse<ProductSummaryResponse> search(AuthenticatedUser user, ProductSearch filter, int page, int size);

    ProductResponse detail(AuthenticatedUser user, long id, Long storeId);

    /**
     * BR-77: exact match on a barcode, trimmed. Branch Manager and Staff always see their own store; the Owner sees
     * {@code storeId}, or no stock when it is null. Discontinued parts are returned too, with their status.
     */
    BarcodeLookupResponse findByBarcode(AuthenticatedUser user, String code, Long storeId);

    /** UC-15. Creates no stock (DBR-02); prices are set afterwards in SCR-25. */
    ProductResponse create(AuthenticatedUser actor, ProductRequest request);

    /**
     * UC-16. A category or vehicle model that has since been deactivated may stay on the part, but a newly
     * chosen one must be in use.
     */
    ProductResponse update(AuthenticatedUser actor, long id, ProductRequest request);

    /** UC-17 and its undo. Stock, prices and old documents are untouched (BR-07). */
    void changeStatus(AuthenticatedUser actor, long id, ProductStatus status);
}
