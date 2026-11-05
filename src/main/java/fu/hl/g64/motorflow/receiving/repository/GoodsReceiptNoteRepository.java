package fu.hl.g64.motorflow.receiving.repository;

import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface GoodsReceiptNoteRepository extends JpaRepository<GoodsReceiptNote, Long> {

    /**
     * A receipt has no warehouse of its own: a supplier delivery arrives at the warehouse of its purchase order,
     * a transfer at the destination warehouse, and goods sent back at the source warehouse.
     */
    String RECEIVED_AT_STORE = """
            ((r.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.SUPPLIER_DELIVERY
              and r.purchaseOrderId in (select q.id from PurchaseOrder q where q.storeId = :storeId))
             or (r.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.TRANSFER_IN
              and r.transferOrderId in (select t.id from TransferOrder t where t.destStoreId = :storeId))
             or (r.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.RETURN_TO_SOURCE
              and r.transferOrderId in (select t.id from TransferOrder t where t.sourceStoreId = :storeId)))""";

    /** Deliveries received against the supplier's purchase orders (UC-32). */
    @Query("""
            select r from GoodsReceiptNote r
            where r.purchaseOrderId in (select q.id from PurchaseOrder q where q.supplierId = :supplierId)""")
    Page<GoodsReceiptNote> findBySupplier(Long supplierId, Pageable pageable);

    @Query("select count(r) from GoodsReceiptNote r where r.status = :status and " + RECEIVED_AT_STORE)
    long countAtStore(Long storeId, GoodsReceiptNoteStatus status);

    /** Receipts waiting for inspection that no Staff member has been given yet. */
    @Query("""
            select count(r) from GoodsReceiptNote r
            where r.status = fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus.AWAITING_INSPECTION
              and not exists (select t.id from WarehouseTask t where t.goodsReceiptNoteId = r.id
                              and t.status <> fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.CANCELLED)
              and """ + RECEIVED_AT_STORE)
    long countAwaitingInspectionWithoutTask(Long storeId);

    /** BR-21: once a GRN is recorded for a PO, the PO can no longer be changed or cancelled. */
    boolean existsByPurchaseOrderId(Long purchaseOrderId);

    /** The deliveries of a PO in the order they arrived (UC-41). */
    List<GoodsReceiptNote> findByPurchaseOrderIdOrderByArrivedAtAscIdAsc(Long purchaseOrderId);
}
