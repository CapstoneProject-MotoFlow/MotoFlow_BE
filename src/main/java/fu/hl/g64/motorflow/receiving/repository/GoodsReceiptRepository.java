package fu.hl.g64.motorflow.receiving.repository;

import fu.hl.g64.motorflow.receiving.entity.GoodsReceipt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, Long> {

    /** Deliveries received against the supplier's goods receipt requests (UC-32). */
    @Query("""
            select r from GoodsReceipt r
            where r.goodsReceiptRequestId in (select q.id from GoodsReceiptRequest q where q.supplierId = :supplierId)""")
    Page<GoodsReceipt> findBySupplier(Long supplierId, Pageable pageable);
}
