package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.StockCount;
import fu.hl.g64.motorflow.inventory.entity.enums.CountType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

public interface StockCountRepository extends JpaRepository<StockCount, Long> {

    long countByStatus(StockCountStatus status);

    /** The count, row-locked, so that two people acting on it at once cannot both change it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StockCount c where c.id = :id")
    Optional<StockCount> findForUpdate(Long id);

    /** SC-1: the warehouse already has a count in one of these statuses. */
    boolean existsByStoreIdAndStatusIn(Long storeId, Collection<StockCountStatus> statuses);

    /**
     * UC-90: [count, store name], newest first; a null filter is not applied, {@code counterId} keeps the counts that
     * person holds a COUNTING task for which was not cancelled.
     */
    @Query(value = """
            select c, s.storeName from StockCount c, Store s
            where s.id = c.storeId
              and (:storeId is null or c.storeId = :storeId)
              and (:countType is null or c.countType = :countType)
              and (:status is null or c.status = :status)
              and (cast(:from as LocalDate) is null or c.countDate >= :from)
              and (cast(:to as LocalDate) is null or c.countDate <= :to)
              and (:counterId is null or exists (select 1 from WarehouseTask t
                   where t.stockCountId = c.id
                     and t.taskType = fu.hl.g64.motorflow.operation.entity.enums.TaskType.COUNTING
                     and t.assignedTo = :counterId
                     and t.status <> fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.CANCELLED))
            order by c.createdAt desc, c.id desc""",
            countQuery = """
            select count(c) from StockCount c
            where (:storeId is null or c.storeId = :storeId)
              and (:countType is null or c.countType = :countType)
              and (:status is null or c.status = :status)
              and (cast(:from as LocalDate) is null or c.countDate >= :from)
              and (cast(:to as LocalDate) is null or c.countDate <= :to)
              and (:counterId is null or exists (select 1 from WarehouseTask t
                   where t.stockCountId = c.id
                     and t.taskType = fu.hl.g64.motorflow.operation.entity.enums.TaskType.COUNTING
                     and t.assignedTo = :counterId
                     and t.status <> fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.CANCELLED))""")
    Page<Object[]> search(Long storeId, CountType countType, StockCountStatus status, LocalDate from, LocalDate to,
                          Long counterId, Pageable pageable);
}
