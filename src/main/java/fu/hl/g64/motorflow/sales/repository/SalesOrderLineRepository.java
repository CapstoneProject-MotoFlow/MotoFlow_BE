package fu.hl.g64.motorflow.sales.repository;

import fu.hl.g64.motorflow.sales.entity.SalesOrderLine;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
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

    /**
     * GVHD 24/11 (UC-59): [line, order, part] of every line of the waiting orders (AWAITING_STOCK) of one store, or of
     * every store when {@code storeId} is null, oldest order first: the order in which stock is shared out (BR-90).
     */
    @Query("""
            select l, o, p
            from SalesOrderLine l
            join SalesOrder o on o.id = l.salesOrderId
            join Product p on p.id = l.productId
            where o.waitedForStock = true
              and o.status = fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus.AWAITING_STOCK
              and (:storeId is null or o.storeId = :storeId)
            order by o.createdAt asc, o.id asc, l.lineNo asc""")
    List<Object[]> findWaitingLines(Long storeId);

    /**
     * GVHD 24/11 (UC-59): [line, order, part] of each line of an order that waited for stock and is now in one of
     * {@code statuses} (released or cancelled), taken (cancelled, for a cancelled order) in [from, to), newest first.
     * {@code pattern} is a {@code SearchText.likePattern} on SKU, part name, customer name (no case or accents) or
     * phone; "%" matches every line.
     */
    @Query("""
            select l, o, p
            from SalesOrderLine l
            join SalesOrder o on o.id = l.salesOrderId
            join Product p on p.id = l.productId
            where o.waitedForStock = true and o.status in :statuses
              and (:storeId is null or o.storeId = :storeId)
              and (:productId is null or l.productId = :productId)
              and (:customerId is null or o.customerId = :customerId)
              and coalesce(o.cancelledAt, o.createdAt) >= :from
              and coalesce(o.cancelledAt, o.createdAt) < :to
              and (search_fold(p.sku) like :pattern escape '\\' or search_fold(p.partName) like :pattern escape '\\'
                   or search_fold(o.customerNameSnapshot) like :pattern escape '\\'
                   or o.customerPhoneSnapshot like :pattern escape '\\')
            order by coalesce(o.cancelledAt, o.createdAt) desc, o.id desc, l.lineNo asc""")
    List<Object[]> findFormerWaitingLines(Collection<SalesOrderStatus> statuses, Long storeId, Long productId,
                                          Long customerId, LocalDateTime from, LocalDateTime to, String pattern);
}
