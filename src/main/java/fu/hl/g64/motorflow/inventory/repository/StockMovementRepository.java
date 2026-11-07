package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.StockMovement;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long>, JpaSpecificationExecutor<StockMovement> {

    /** UC-98: has this lot ever moved in this warehouse? */
    boolean existsByLotIdAndStoreId(Long lotId, Long storeId);

    /** UC-98: the latest movements of a lot in one warehouse. */
    List<StockMovement> findTop10ByLotIdAndStoreIdOrderByOccurredAtDescIdDesc(Long lotId, Long storeId);

    /** UC-98: what came into a warehouse through the given movement types, from one stock count when given. */
    @Query("""
            select coalesce(sum(m.quantityChange), 0) from StockMovement m
            where m.lotId = :lotId and m.storeId = :storeId and m.quantityChange > 0 and m.movementType in :types
              and (:stockCountId is null or m.stockCountId = :stockCountId)""")
    long sumIncoming(Long lotId, Long storeId, Long stockCountId, Collection<MovementType> types);
}
