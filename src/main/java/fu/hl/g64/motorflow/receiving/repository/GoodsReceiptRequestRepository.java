package fu.hl.g64.motorflow.receiving.repository;

import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoodsReceiptRequestRepository extends JpaRepository<GoodsReceiptRequest, Long> {

    Page<GoodsReceiptRequest> findBySupplierId(Long supplierId, Pageable pageable);
}
