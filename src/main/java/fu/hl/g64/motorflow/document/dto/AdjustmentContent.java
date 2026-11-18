package fu.hl.g64.motorflow.document.dto;

import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentType;
import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;

import java.time.LocalDateTime;
import java.util.List;

/** An adjustment request: a stock loss (UC-83) or a document correction (UC-81), with what it changes. */
public record AdjustmentContent(long id, AdjustmentType adjustmentType, AdjustmentRequestStatus status, long storeId,
                                String reason, Long requestedById, String requestedBy, LocalDateTime createdAt,
                                LocalDateTime submittedAt, Long decidedById, String decidedBy, LocalDateTime decidedAt,
                                String decisionReason, LocalDateTime appliedAt, List<StockLine> stockLines,
                                List<AmountLine> amountLines, List<AttachmentResponse> evidence) {

    /** A quantity change of one part, lot and stock status in a store; negative takes stock out. */
    public record StockLine(long storeId, long productId, String sku, String partName, String baseUnit, Long lotId,
                            String manufacturerLotNo, String stockStatus, int quantityChange) {
    }

    /** A change of a payment's or a receivable's amount. */
    public record AmountLine(Long paymentId, Long receivableId, long amountChange) {
    }
}
