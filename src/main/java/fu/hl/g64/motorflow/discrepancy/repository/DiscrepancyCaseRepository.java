package fu.hl.g64.motorflow.discrepancy.repository;

import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface DiscrepancyCaseRepository extends JpaRepository<DiscrepancyCase, Long> {

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
}
