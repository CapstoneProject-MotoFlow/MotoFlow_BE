package fu.hl.g64.motorflow.stockcount.dto;

import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountRequestStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-105 and the UC-91 prefill: the Owner's stock count request. {@code products} is empty unless the scope is
 * PRODUCTS; {@code scopeNote} is the storage area for AREA.
 */
public record CountRequestResponse(
        long id,
        long storeId,
        String storeName,
        ScopeType scopeType,
        String scopeNote,
        String reason,
        StockCountRequestStatus status,
        long requestedBy,
        String requestedByName,
        LocalDateTime requestedAt,
        LocalDateTime cancelledAt,
        List<Product> products) {

    public record Product(long productId, String sku, String partName) {
    }
}
