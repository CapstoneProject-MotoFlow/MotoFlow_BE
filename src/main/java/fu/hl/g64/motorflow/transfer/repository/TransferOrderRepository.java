package fu.hl.g64.motorflow.transfer.repository;

import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TransferOrderRepository extends JpaRepository<TransferOrder, Long> {

    long countBySourceStoreIdAndStatus(Long sourceStoreId, TransferOrderStatus status);

    /** UC-70: a transfer order created for a replenishment request. */
    interface LinkedOrder {
        TransferOrder getTransferOrder();

        String getSourceStoreName();
    }

    @Query("""
            select o as transferOrder, s.storeName as sourceStoreName
            from TransferOrder o
            join Store s on s.id = o.sourceStoreId
            where o.replenishmentRequestId = :requestId
            order by o.id
            """)
    List<LinkedOrder> findLinkedOrders(@Param("requestId") long requestId);
}
