package fu.hl.g64.motorflow.sales.repository;

import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long>, JpaSpecificationExecutor<SalesOrder> {

    /** Row lock: updating, picking and confirming one order happen one after another (UC-55, UC-57, UC-58). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SalesOrder o where o.id = :id")
    Optional<SalesOrder> findByIdForUpdate(long id);

    /** Orders for the customer, at one store or at every store when {@code storeId} is null. */
    @Query("""
            select o from SalesOrder o
            where o.customerId = :customerId and (:storeId is null or o.storeId = :storeId)""")
    Page<SalesOrder> findByCustomer(Long customerId, Long storeId, Pageable pageable);

    long countByStoreIdAndStatus(Long storeId, SalesOrderStatus status);

    /** Orders of the store waiting to be picked that no Staff member has been given yet. */
    @Query("""
            select count(o) from SalesOrder o
            where o.storeId = :storeId
              and o.status = fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus.AWAITING_PICKING
              and not exists (select t.id from WarehouseTask t where t.salesOrderId = o.id
                              and t.status <> fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.CANCELLED)""")
    long countAwaitingPickingWithoutTask(Long storeId);
}
