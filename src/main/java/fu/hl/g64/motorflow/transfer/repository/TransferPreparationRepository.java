package fu.hl.g64.motorflow.transfer.repository;

import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.TransferOrderLine;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Reads for preparing a transfer dispatch (UC-57), kept apart from the transfer repositories that UC-72..79 grow.
 */
public interface TransferPreparationRepository extends Repository<TransferOrder, Long> {

    Optional<TransferOrder> findById(Long id);

    /** Row lock: recording the prepared quantities and confirming the dispatch happen one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from TransferOrder o where o.id = :id")
    Optional<TransferOrder> findByIdForUpdate(long id);

    @Query("select l from TransferOrderLine l where l.transferOrderId = :transferOrderId order by l.id")
    List<TransferOrderLine> findLines(long transferOrderId);
}
