package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;

import java.time.LocalDateTime;
import java.util.List;

/** UC-71: the order as created. */
public record TransferOrderResponse(long id, TransferOrderStatus status, Long replenishmentRequestId, long sourceStoreId,
                                    long destStoreId, long createdBy, LocalDateTime createdAt, List<Line> lines) {

    public record Line(long productId, int plannedQty, Long makeupForCaseId) {
    }
}
