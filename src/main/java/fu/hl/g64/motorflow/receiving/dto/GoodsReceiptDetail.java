package fu.hl.g64.motorflow.receiving.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A GRN as the person opening it may see it: Staff recording it (UC-43 step 2), the Branch Manager confirming it
 * (UC-44 step 2) or Owner deciding its excess (UC-45 step 2). Purchase prices are null except for Owner (BR-72).
 * {@code replacement}: a replacement delivery for the cases in {@code replacementForCaseIds}. The {@code can...}
 * flags tell what the signed-in user may do now.
 */
public record GoodsReceiptDetail(
        long id, ReceiptType receiptType, GoodsReceiptNoteStatus status, boolean replacement,
        List<Long> replacementForCaseIds,
        long purchaseOrderId, long supplierId, String supplierCode, String supplierName,
        long storeId, String storeName,
        String deliveryDocumentNo, LocalDate deliveryDocumentDate, String delivererName, String vehiclePlateNo,
        LocalDateTime arrivedAt, Assignee assignee,
        Long recordedById, String recordedByName, LocalDateTime submittedAt, String recountReason,
        Long confirmedById, String confirmedByName, LocalDateTime confirmedAt,
        Long excessDecidedById, String excessDecidedByName, LocalDateTime excessDecidedAt,
        long openDiscrepancies,
        boolean canRecord, boolean canConfirm, boolean canReturnForRecount, boolean canDecideExcess,
        List<Line> lines, List<AttachmentResponse> documents) {

    /** Who holds the inspection now (UC-86). */
    public record Assignee(long id, String fullName, WarehouseTaskStatus taskStatus, LocalDateTime assignedAt) {
    }

    /**
     * One part. {@code expectedQty}: what the PO (or, for a replacement delivery, the cases) still expects of the part
     * now, null once the GRN is confirmed. {@code toStockQty} and {@code excessQty}: what goes into available stock and
     * what is excess waiting for Owner; computed against {@code expectedQty} until the confirmation fixes them
     * (UC-44 step 5, BR-67). {@code shortQty} = document - delivered (BR-24). {@code lotId}: the lot the confirmation
     * created.
     */
    public record Line(long id, long productId, String sku, String partName, String baseUnit, String binLocation,
                       Integer expectedQty, int documentQty, int deliveredQty, int passedQty, int wrongItemQty,
                       int damagedQty, int shortQty, int toStockQty, int excessQty, ExcessDecision excessDecision,
                       Long unitPrice, Long lotId, String manufacturerLotNo, LocalDate manufactureDate,
                       LocalDate expiryDate) {
    }
}
