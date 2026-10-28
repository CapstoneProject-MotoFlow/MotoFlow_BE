package fu.hl.g64.motorflow.discrepancy.repository;

import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DiscrepancyCaseRepository extends JpaRepository<DiscrepancyCase, Long> {

    /** Cases opened on deliveries of the supplier (UC-32). */
    @Query("""
            select c from DiscrepancyCase c
            where c.sourceType = fu.hl.g64.motorflow.discrepancy.entity.enums.SourceType.SUPPLIER
              and c.goodsReceiptId in (select r.id from GoodsReceipt r where r.goodsReceiptRequestId in
                  (select q.id from GoodsReceiptRequest q where q.supplierId = :supplierId))""")
    Page<DiscrepancyCase> findBySupplier(Long supplierId, Pageable pageable);

    @Query("""
            select count(c) from DiscrepancyCase c
            where c.sourceType = fu.hl.g64.motorflow.discrepancy.entity.enums.SourceType.SUPPLIER and c.status = :status
              and c.goodsReceiptId in (select r.id from GoodsReceipt r where r.goodsReceiptRequestId in
                  (select q.id from GoodsReceiptRequest q where q.supplierId = :supplierId))""")
    long countBySupplierAndStatus(Long supplierId, DiscrepancyCaseStatus status);

    /** Open cases on supplier deliveries received at the warehouse. */
    @Query("""
            select count(c) from DiscrepancyCase c
            where c.sourceType = fu.hl.g64.motorflow.discrepancy.entity.enums.SourceType.SUPPLIER
              and c.status = fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus.OPEN
              and c.goodsReceiptId in (select r.id from GoodsReceipt r where r.goodsReceiptRequestId in
                  (select q.id from GoodsReceiptRequest q where q.warehouseId = :warehouseId))""")
    long countOpenSupplierCasesAtWarehouse(Long warehouseId);
}
