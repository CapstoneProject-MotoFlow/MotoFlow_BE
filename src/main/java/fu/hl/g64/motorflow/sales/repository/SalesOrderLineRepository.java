package fu.hl.g64.motorflow.sales.repository;

import fu.hl.g64.motorflow.sales.entity.SalesOrderLine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface SalesOrderLineRepository extends JpaRepository<SalesOrderLine, Long> {

    /**
     * [salesOrderId, total in VND] of each order: the delivered quantity once Branch Manager has confirmed it,
     * the requested quantity before.
     */
    @Query("""
            select l.salesOrderId, sum(coalesce(l.deliveredQty, l.requestedQty) * l.actualUnitPrice)
            from SalesOrderLine l
            where l.salesOrderId in :orderIds
            group by l.salesOrderId""")
    List<Object[]> currentTotals(Collection<Long> orderIds);

    /** The lines of one order in the order they were added. */
    List<SalesOrderLine> findBySalesOrderIdOrderByLineNoAsc(Long salesOrderId);

    String UNMET_DEMAND = """
            from SalesOrderLine l
            join SalesOrder o on o.id = l.salesOrderId
            join Product p on p.id = l.productId
            where ((o.status = fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus.COMPLETED
                    and l.deliveredQty < l.requestedQty)
                   or o.status = fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus.CANCELLED)
              and (:storeId is null or o.storeId = :storeId)
              and (:productId is null or l.productId = :productId)
              and (:customerId is null or o.customerId = :customerId)
              and coalesce(o.completedAt, o.cancelledAt) >= :from and coalesce(o.completedAt, o.cancelledAt) < :to
              and (lower(p.sku) like :pattern or lower(p.partName) like :pattern
                   or lower(o.customerNameSnapshot) like :pattern or o.customerPhoneSnapshot like :pattern)""";

    /**
     * UC-59 step 2: [line, order, part] of each line a completed order delivered less of than requested, and of each
     * line of a cancelled order, completed or cancelled in [from, to), newest first. {@code pattern} is a lower-case
     * LIKE pattern on SKU, part name, customer name or phone; "%" matches every line.
     */
    @Query(value = "select l, o, p " + UNMET_DEMAND
            + " order by coalesce(o.completedAt, o.cancelledAt) desc, o.id desc, l.lineNo asc",
            countQuery = "select count(l) " + UNMET_DEMAND)
    Page<Object[]> findUnmetDemand(Long storeId, Long productId, Long customerId, LocalDateTime from,
                                   LocalDateTime to, String pattern, Pageable pageable);
}
