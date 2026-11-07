package fu.hl.g64.motorflow.transfer.repository;

import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
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

    /** Staff work on an order through preparing its dispatch or inspecting one of its receipts. */
    String STAFF_TASK = """
            exists (select 1 from WarehouseTask t
                    where t.assignedTo = :staffId
                      and t.status <> fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.CANCELLED
                      and (t.transferOrderId = o.id
                           or t.goodsReceiptNoteId in (select g.id from GoodsReceiptNote g
                                                       where g.transferOrderId = o.id)))
            """;

    String SEARCH = """
            where (:status is null or o.status = :status)
              and (:sourceStoreId is null or o.sourceStoreId = :sourceStoreId)
              and (:destStoreId is null or o.destStoreId = :destStoreId)
              and (:requestId is null or o.replenishmentRequestId = :requestId)
              and (:storeId is null or o.sourceStoreId = :storeId or o.destStoreId = :storeId)
              and (:staffId is null or
            """ + STAFF_TASK + """
            )
              and cast(o.createdAt as LocalDate) >= coalesce(:from, cast(o.createdAt as LocalDate))
              and cast(o.createdAt as LocalDate) <= coalesce(:to, cast(o.createdAt as LocalDate))
            """;

    /** UC-72: one order with the totals of its lines. */
    interface OrderRow {
        TransferOrder getTransferOrder();

        String getSourceStoreName();

        String getDestStoreName();

        String getCreatedByName();

        long getLineCount();

        long getTotalPlannedQty();
    }

    /** UC-72: {@code storeId} keeps a Branch Manager to orders from or to their store, {@code staffId} Staff to theirs. */
    @Query(value = """
            select o as transferOrder, s.storeName as sourceStoreName, d.storeName as destStoreName,
                   u.fullName as createdByName,
                   (select count(l) from TransferOrderLine l where l.transferOrderId = o.id) as lineCount,
                   (select coalesce(sum(l.plannedQty), 0) from TransferOrderLine l
                    where l.transferOrderId = o.id) as totalPlannedQty
            from TransferOrder o
            join Store s on s.id = o.sourceStoreId
            join Store d on d.id = o.destStoreId
            join UserAccount u on u.id = o.createdBy
            """ + SEARCH + """
            order by case when o.status = fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus.CLOSED
                          then 1 else 0 end,
                     o.createdAt desc, o.id desc
            """,
            countQuery = "select count(o) from TransferOrder o " + SEARCH)
    Page<OrderRow> search(@Param("status") TransferOrderStatus status, @Param("sourceStoreId") Long sourceStoreId,
                          @Param("destStoreId") Long destStoreId, @Param("requestId") Long requestId,
                          @Param("storeId") Long storeId, @Param("staffId") Long staffId,
                          @Param("from") LocalDate from, @Param("to") LocalDate to, Pageable pageable);

    @Query("select count(o) > 0 from TransferOrder o where o.id = :id and " + STAFF_TASK)
    boolean staffWorksOn(@Param("id") long id, @Param("staffId") long staffId);

    @Query("select g from GoodsReceiptNote g where g.transferOrderId = :id order by g.id")
    List<GoodsReceiptNote> findReceipts(@Param("id") long id);

    @Query("select c from DiscrepancyCase c where c.transferOrderId = :id order by c.productId")
    List<DiscrepancyCase> findCases(@Param("id") long id);

    @Query("""
            select e from DiscrepancyResolutionEvent e
            where e.discrepancyCaseId in :caseIds
            order by e.occurredAt, e.id
            """)
    List<DiscrepancyResolutionEvent> findEvents(@Param("caseIds") Collection<Long> caseIds);

    /** UC-73: a lot taken out of the source on dispatch. */
    interface DispatchedLot {
        long getProductId();

        long getLotId();

        String getManufacturerLotNo();

        LocalDate getExpiryDate();

        long getQuantity();

        BigDecimal getUnitCost();
    }

    @Query("""
            select m.productId as productId, m.lotId as lotId, l.manufacturerLotNo as manufacturerLotNo,
                   l.expiryDate as expiryDate, -sum(m.quantityChange) as quantity, max(m.unitCost) as unitCost
            from StockMovement m
            join Lot l on l.id = m.lotId
            where m.transferOrderId = :id
              and m.movementType = fu.hl.g64.motorflow.inventory.entity.enums.MovementType.TRANSFER_DISPATCH
              and m.quantityChange < 0
            group by m.productId, m.lotId, l.manufacturerLotNo, l.expiryDate
            order by m.productId, l.expiryDate nulls last, m.lotId
            """)
    List<DispatchedLot> findDispatchedLots(@Param("id") long id);
}
