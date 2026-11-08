package fu.hl.g64.motorflow.stockcount.dto;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.inventory.entity.enums.CountType;
import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-90: the counts the user may see, and for a Branch Manager the Owner's PENDING requests to their store (empty for
 * everyone else). An empty page is no error; the screen shows MSG01.
 */
public record StockCountListResponse(PageResponse<Row> counts, List<CountRequestResponse> pendingRequests) {

    public record Row(
            long id,
            long storeId,
            String storeName,
            CountType countType,
            LocalDate countDate,
            ScopeType scopeType,
            String scopeNote,
            StockCountStatus status,
            Long stockCountRequestId,
            LocalDateTime createdAt,
            LocalDateTime confirmedAt) {
    }
}
