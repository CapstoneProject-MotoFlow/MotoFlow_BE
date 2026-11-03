package fu.hl.g64.motorflow.catalog.service;

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
    public PageResponse<ProductSummaryResponse> search(AuthenticatedUser user, ProductSearch filter, int page, int size);

    public ProductResponse detail(AuthenticatedUser user, long id, Long storeId);

    /** UC-15. Creates no stock (DBR-02); prices are set afterwards in SCR-25. */
    public ProductResponse create(AuthenticatedUser actor, ProductRequest request);

    /**
     * UC-16. A category or vehicle model that has since been deactivated may stay on the part, but a newly
     * chosen one must be in use.
     */
    public ProductResponse update(AuthenticatedUser actor, long id, ProductRequest request);

    /** UC-17 and its undo. Stock, prices and old documents are untouched (BR-07). */
    public void changeStatus(AuthenticatedUser actor, long id, ProductStatus status);
}
