package fu.hl.g64.motorflow.receiving.repository;

import fu.hl.g64.motorflow.receiving.entity.GoodsReceipt;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, Long> {

    /**
     * A receipt has no warehouse of its own: a supplier delivery arrives at the warehouse of its goods receipt request,
     * a transfer at the destination warehouse, and goods sent back at the source warehouse.
     */
    String RECEIVED_AT_STORE = """
            ((r.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.SUPPLIER_DELIVERY
              and r.goodsReceiptRequestId in (select q.id from GoodsReceiptRequest q where q.storeId = :storeId))
             or (r.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.TRANSFER_IN
              and r.transferOrderId in (select t.id from TransferOrder t where t.destStoreId = :storeId))
             or (r.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.RETURN_TO_SOURCE
              and r.transferOrderId in (select t.id from TransferOrder t where t.sourceStoreId = :storeId)))""";

    /** Deliveries received against the supplier's goods receipt requests (UC-32). */
    @Query("""
            select r from GoodsReceipt r
            where r.goodsReceiptRequestId in (select q.id from GoodsReceiptRequest q where q.supplierId = :supplierId)""")
    Page<GoodsReceipt> findBySupplier(Long supplierId, Pageable pageable);

    @Query("select count(r) from GoodsReceipt r where r.status = :status and " + RECEIVED_AT_STORE)
    long countAtStore(Long storeId, GoodsReceiptStatus status);

    /** Receipts waiting for inspection that no Staff member has been given yet. */
    @Query("""
            select count(r) from GoodsReceipt r
            where r.status = fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptStatus.AWAITING_INSPECTION
              and not exists (select t.id from WarehouseTask t where t.goodsReceiptId = r.id
                              and t.status <> fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.CANCELLED)
              and """ + RECEIVED_AT_STORE)
    long countAwaitingInspectionWithoutTask(Long storeId);
}
