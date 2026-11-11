package fu.hl.g64.motorflow.transfer.repository;

import fu.hl.g64.motorflow.receiving.entity.PurchaseOrder;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequest;
import fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ReplenishmentRequestRepository extends JpaRepository<ReplenishmentRequest, Long> {

    long countByStatus(ReplenishmentRequestStatus status);

    /** UC-71: orders for one request and its purchase hand-over are planned one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReplenishmentRequest r where r.id = :id")
    Optional<ReplenishmentRequest> findByIdForUpdate(@Param("id") long id);

    /** UC-69: one request with the totals of its lines. */
    interface RequestRow {
        ReplenishmentRequest getRequest();

        String getStoreName();

        String getCreatedByName();

        long getLineCount();

        long getTotalRequestedQty();
    }

    /** UC-70: a PO created for the request. */
    interface PurchaseOrderRow {
        PurchaseOrder getPurchaseOrder();

        String getSupplierName();
    }

    String SEARCH = """
            where (:status is null or r.status = :status)
              and (:storeId is null or r.requestingStoreId = :storeId)
              and cast(r.createdAt as LocalDate) >= coalesce(:from, cast(r.createdAt as LocalDate))
              and cast(r.createdAt as LocalDate) <= coalesce(:to, cast(r.createdAt as LocalDate))
              and (:hasQ = false or r.id = :qId
                   or r.requestingStoreId in (select qs.id from Store qs where lower(qs.storeName) like :pattern escape '\\'))
            """;

    @Query(value = """
            select r as request, s.storeName as storeName, u.fullName as createdByName,
                   (select count(l) from ReplenishmentRequestLine l
                    where l.replenishmentRequestId = r.id) as lineCount,
                   (select coalesce(sum(l.requestedQty), 0) from ReplenishmentRequestLine l
                    where l.replenishmentRequestId = r.id) as totalRequestedQty
            from ReplenishmentRequest r
            join Store s on s.id = r.requestingStoreId
            join UserAccount u on u.id = r.createdBy
            """ + SEARCH + """
            order by case when r.status = fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus.AWAITING_COORDINATION
                          then 0 else 1 end,
                     r.createdAt desc, r.id desc
            """,
            countQuery = "select count(r) from ReplenishmentRequest r " + SEARCH)
    Page<RequestRow> search(@Param("status") ReplenishmentRequestStatus status, @Param("storeId") Long storeId,
                            @Param("from") LocalDate from, @Param("to") LocalDate to,
                            @Param("hasQ") boolean hasQ, @Param("qId") Long qId, @Param("pattern") String pattern,
                            Pageable pageable);

    @Query("""
            select p as purchaseOrder, s.supplierName as supplierName
            from PurchaseOrder p
            join Supplier s on s.id = p.supplierId
            where p.replenishmentRequestId = :requestId
            order by p.id
            """)
    List<PurchaseOrderRow> findPurchaseOrders(@Param("requestId") long requestId);
}
