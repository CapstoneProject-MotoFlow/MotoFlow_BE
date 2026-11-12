package fu.hl.g64.motorflow.document.dto;

import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-79: a document with its source documents, related records, adjustments and change history.
 * {@code content} is the document as its own screen shows it: SalesOrderDetail, GoodsReceiptDetail (supplier
 * delivery), TransferReceiptResponse (transferred goods), TransferOrderDetailResponse, {@link PaymentContent} or
 * {@link AdjustmentContent}; prices and costs are hidden from everyone but Owner (BR-72) as on those screens.
 */
public record DocumentDetail(DocumentRow document, Object content, List<Link> sources, List<Link> related,
                            List<Adjustment> adjustments, List<HistoryEntry> history) {

    /**
     * Another record the document comes from or led to. {@code recordType} names it (SALES_ORDER, PURCHASE_ORDER,
     * RECEIVABLE, DISCREPANCY_CASE, ...); {@code documentType} is set when it is a posted document UC-79 can open.
     */
    public record Link(String recordType, long id, String number, String status, DocumentType documentType,
                       String note) {
    }

    /** An adjustment request made against the document (submitted ones only). */
    public record Adjustment(long id, String number, AdjustmentType adjustmentType, AdjustmentRequestStatus status,
                             String reason, LocalDateTime submittedAt, LocalDateTime decidedAt,
                             LocalDateTime appliedAt) {
    }

    /** One change of the document, oldest first. */
    public record HistoryEntry(String action, Long actorId, String actorName, LocalDateTime occurredAt,
                               String reason) {
    }
}
