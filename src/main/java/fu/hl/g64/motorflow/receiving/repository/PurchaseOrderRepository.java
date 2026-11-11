package fu.hl.g64.motorflow.receiving.repository;

import fu.hl.g64.motorflow.receiving.entity.PurchaseOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long>,
        JpaSpecificationExecutor<PurchaseOrder> {

    Page<PurchaseOrder> findBySupplierId(Long supplierId, Pageable pageable);

    /**
     * The PO row, locked. Changing or cancelling a PO (UC-42) and recording its first GRN (UC-106) both lock it first,
     * so that a PO cannot change after a GRN was recorded for it (BR-21).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PurchaseOrder p where p.id = :id")
    Optional<PurchaseOrder> findByIdForUpdate(long id);
}
