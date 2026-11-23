package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.transfer.helper.SourceLots.PlannedLot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Plan 23/11: a phiếu A awaiting the Owner. {@code productId} is the part ordered, {@code receivedProductId} the part
 * the wrong items really are; {@code lots} the source lots a confirmation would take now and {@code blockedCode}
 * MSG341 or MSG339 when it would be refused, with {@code sourceSellableQty} what the source can give.
 */
public record PendingWrongItemResponse(long eventId, long transferOrderId, long sourceStoreId, String sourceStoreName,
                                       long destStoreId, String destStoreName, long productId, String sku,
                                       String partName, long receivedProductId, String receivedSku,
                                       String receivedPartName, int quantity, LocalDate expiryDate, String reason,
                                       String recordedByName, LocalDateTime recordedAt, int sourceSellableQty,
                                       List<PlannedLot> lots, String blockedCode) {
}
