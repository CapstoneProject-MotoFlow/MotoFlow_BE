package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;
import fu.hl.g64.motorflow.transfer.helper.SourceLots.PlannedLot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Plan 23/11: a phiếu A awaiting the source's BM (GVHD 24/11; the Owner views it). {@code productId} is the part ordered, {@code receivedProductId} the part
 * the wrong items really are; {@code lots} the source lots a confirmation would take now and {@code blockedCode}
 * MSG341 or MSG339 when it would be refused, with {@code sourceSellableQty} what the source can give;
 * {@code attachments} the photos the destination's BM took. Review 25/11 (R-14): {@code resolutionType} is
 * RECEIVED_AS_OTHER_PRODUCT for a phiếu or WRONG_ITEM_RETURNED for a hand-back, which moves nothing (no lots).
 */
public record PendingWrongItemResponse(long eventId, long transferOrderId, long sourceStoreId, String sourceStoreName,
                                       long destStoreId, String destStoreName, long productId, String sku,
                                       String partName, long receivedProductId, String receivedSku,
                                       String receivedPartName, int quantity, LocalDate expiryDate, String reason,
                                       String recordedByName, LocalDateTime recordedAt, int sourceSellableQty,
                                       List<PlannedLot> lots, String blockedCode,
                                       List<AttachmentResponse> attachments, ResolutionType resolutionType) {
}
