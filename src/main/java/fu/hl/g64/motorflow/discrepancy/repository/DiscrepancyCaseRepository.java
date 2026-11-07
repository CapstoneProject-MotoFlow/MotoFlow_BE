package fu.hl.g64.motorflow.discrepancy.repository;

import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DiscrepancyCaseRepository extends JpaRepository<DiscrepancyCase, Long>,
        JpaSpecificationExecutor<DiscrepancyCase> {

    /** Cases opened on deliveries of the supplier (UC-32). */
    @Query("""
            select c from DiscrepancyCase c
            where c.sourceType = fu.hl.g64.motorflow.discrepancy.entity.enums.SourceType.SUPPLIER
              and c.goodsReceiptNoteId in (select r.id from GoodsReceiptNote r where r.purchaseOrderId in
                  (select q.id from PurchaseOrder q where q.supplierId = :supplierId))""")
    Page<DiscrepancyCase> findBySupplier(Long supplierId, Pageable pageable);

    @Query("""
            select count(c) from DiscrepancyCase c
            where c.sourceType = fu.hl.g64.motorflow.discrepancy.entity.enums.SourceType.SUPPLIER and c.status = :status
              and c.goodsReceiptNoteId in (select r.id from GoodsReceiptNote r where r.purchaseOrderId in
                  (select q.id from PurchaseOrder q where q.supplierId = :supplierId))""")
    long countBySupplierAndStatus(Long supplierId, DiscrepancyCaseStatus status);

    /** Open cases on supplier deliveries received at the warehouse. */
    @Query("""
            select count(c) from DiscrepancyCase c
            where c.sourceType = fu.hl.g64.motorflow.discrepancy.entity.enums.SourceType.SUPPLIER
              and c.status = fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus.OPEN
              and c.goodsReceiptNoteId in (select r.id from GoodsReceiptNote r where r.purchaseOrderId in
                  (select q.id from PurchaseOrder q where q.storeId = :storeId))""")
    long countOpenSupplierCasesAtStore(Long storeId);

    /** Cases opened on the given deliveries (UC-41). */
    List<DiscrepancyCase> findByGoodsReceiptNoteIdInOrderByIdAsc(Collection<Long> goodsReceiptNoteIds);

    /** [purchaseOrderId, open cases on its deliveries] of the given POs (UC-39). */
    @Query("""
            select r.purchaseOrderId, count(c) from DiscrepancyCase c, GoodsReceiptNote r
            where c.goodsReceiptNoteId = r.id and r.purchaseOrderId in :purchaseOrderIds
              and c.status = fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus.OPEN
            group by r.purchaseOrderId""")
    List<Object[]> countOpenByPurchaseOrder(Collection<Long> purchaseOrderIds);

    /**
     * Cases, locked in id order. Every change of a case (UC-46, a replacement GRN confirmed, excess rejected) locks
     * the cases before it posts stock, so the lock order is always: PO, GRN, cases, then stock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from DiscrepancyCase c where c.id in :ids order by c.id")
    List<DiscrepancyCase> findAllByIdForUpdate(Collection<Long> ids);

    /** The receipt a case was opened on, read without loading the case (see {@link #findAllByIdForUpdate}). */
    @Query("select c.goodsReceiptNoteId from DiscrepancyCase c where c.id = :id")
    Optional<Long> findReceiptIdOf(long id);

    /** [goodsReceiptNoteId, open cases] of the given receipts. */
    @Query("""
            select c.goodsReceiptNoteId, count(c) from DiscrepancyCase c
            where c.goodsReceiptNoteId in :receiptIds
              and c.status = fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus.OPEN
            group by c.goodsReceiptNoteId""")
    List<Object[]> countOpenByReceipt(Collection<Long> receiptIds);

    /** The cases opened on one receipt, locked in id order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from DiscrepancyCase c where c.goodsReceiptNoteId = :receiptId order by c.id")
    List<DiscrepancyCase> findByReceiptForUpdate(long receiptId);
}
