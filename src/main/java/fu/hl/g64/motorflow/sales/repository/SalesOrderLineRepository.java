package fu.hl.g64.motorflow.sales.repository;

import fu.hl.g64.motorflow.common.text.SearchText;
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

    /** What the lines of open sales orders hold (BR-33, GVHD 20/11), as JPQL on {@code l} and {@code o}. */
    String HELD = """
            sum(case
                when o.status = fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus.AWAITING_PICKING
                    then l.requestedQty
                when o.status = fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus.PICKED
                    then coalesce(l.pickedQty, l.requestedQty)
                else coalesce(l.deliveredQty, l.pickedQty, l.requestedQty) end)""";

    /** The open sales orders that hold goods: waiting for picking, picked and confirmed. */
    String HOLDING = " " + """
            o.status in (fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus.AWAITING_PICKING,
                         fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus.PICKED,
                         fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus.CONFIRMED)""";

    /**
     * GVHD 20/11 (BR-33): [productId, quantity held] of the store's open sales orders other than
     * {@code excludeOrderId} (-1 for none): waiting for picking holds what was requested, picked what was picked,
     * confirmed what is delivered. Drafts and waiting orders hold nothing; the goods leave stock at completion
     * (SALE_ISSUE).
     */
    @Query("select l.productId, " + HELD + """
             from SalesOrderLine l
            join SalesOrder o on o.id = l.salesOrderId
            where o.storeId = :storeId and l.productId in :productIds and o.id <> :excludeOrderId and """ + HOLDING
            + " group by l.productId")
    List<Object[]> reservedQuantities(long storeId, Collection<Long> productIds, long excludeOrderId);

    /** GVHD 20/11: [storeId, productId, quantity held] of every store's open sales orders for these parts. */
    @Query("select o.storeId, l.productId, " + HELD + """
             from SalesOrderLine l
            join SalesOrder o on o.id = l.salesOrderId
            where l.productId in :productIds and """ + HOLDING + " group by o.storeId, l.productId")
    List<Object[]> reservedByStore(Collection<Long> productIds);

    String UNMET_DEMAND = """
            from SalesOrderLine l
            join SalesOrder o on o.id = l.salesOrderId
            join Product p on p.id = l.productId
            where ((o.status = fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus.COMPLETED
                    and l.deliveredQty < l.requestedQty)
                   or o.status = fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus.CANCELLED
                   or o.status = fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus.AWAITING_STOCK)
              and (:storeId is null or o.storeId = :storeId)
              and (:productId is null or l.productId = :productId)
              and (:customerId is null or o.customerId = :customerId)
              and coalesce(o.completedAt, o.cancelledAt, o.createdAt) >= :from
              and coalesce(o.completedAt, o.cancelledAt, o.createdAt) < :to
              and (search_fold(p.sku) like :pattern escape '\\' or search_fold(p.partName) like :pattern escape '\\'
                   or search_fold(o.customerNameSnapshot) like :pattern escape '\\'
                   or o.customerPhoneSnapshot like :pattern escape '\\')""";

    /**
     * UC-59 step 2: [line, order, part] of each line a completed order delivered less of than requested, and of each
     * line of a cancelled order, completed or cancelled in [from, to), and (góp ý UI 21/11) of each line still waiting
     * on a waiting order (AWAITING_STOCK) created in [from, to), newest first. {@code pattern} is a
     * {@code SearchText.likePattern} on SKU, part name, customer name (no case or accents) or phone; "%" matches
     * every line.
     */
    @Query(value = "select l, o, p " + UNMET_DEMAND
            + " order by coalesce(o.completedAt, o.cancelledAt, o.createdAt) desc, o.id desc, l.lineNo asc",
            countQuery = "select count(l) " + UNMET_DEMAND)
    Page<Object[]> findUnmetDemand(Long storeId, Long productId, Long customerId, LocalDateTime from,
                                   LocalDateTime to, String pattern, Pageable pageable);
}
