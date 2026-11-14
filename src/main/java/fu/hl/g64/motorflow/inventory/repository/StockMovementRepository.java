package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.StockMovement;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long>, JpaSpecificationExecutor<StockMovement> {

    /** Shared by the page and count queries so they cannot drift; closing = period closing + movements in the window. */
    String PAST_DATE_FROM = """
            from product p
            left join period_stock_balance psb on psb.inventory_period_id = cast(:periodId as bigint)
                 and psb.store_id = :storeId and psb.product_id = p.id
            left join (select product_id, sum(quantity_change) qty from stock_movement
                       where store_id = :storeId and stock_status <> 'IN_TRANSIT'
                         and occurred_at >= :from and occurred_at < :to
                       group by product_id) m on m.product_id = p.id
            where (exists (select 1 from stock_balance sb where sb.store_id = :storeId and sb.product_id = p.id)
                   or exists (select 1 from stock_movement x where x.store_id = :storeId and x.product_id = p.id
                              and x.occurred_at < :to)
                   or psb.id is not null)
              and (cast(:q as text) is null or lower(p.sku) like lower(concat('%', cast(:q as text), '%'))
                   or lower(p.part_name) like lower(concat('%', cast(:q as text), '%')))
              and (not :filterCategory or p.category_id in (:categoryIds))
              and (:includeZero or coalesce(psb.closing_qty, 0) + coalesce(m.qty, 0) <> 0)""";

    /** UC-103: parts with their closing quantity, by SKU; {@code categoryIds} must not be empty. */
    @Query(nativeQuery = true, value = """
            select p.id as "productId", p.sku as "sku", p.part_name as "partName", p.base_unit as "baseUnit",
                   coalesce(psb.closing_qty, 0) + coalesce(m.qty, 0) as "closingQty"
            """ + PAST_DATE_FROM + " order by p.sku", countQuery = "select count(*) " + PAST_DATE_FROM)
    Page<PastDateLine> pastDateStock(Long periodId, long storeId, LocalDateTime from, LocalDateTime to, String q,
                                     boolean filterCategory, Collection<Long> categoryIds, boolean includeZero,
                                     Pageable pageable);

    interface PastDateLine {
        Long getProductId();

        String getSku();

        String getPartName();

        String getBaseUnit();

        Integer getClosingQty();
    }

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

    /** [adjustment request id, sum of the value its movements posted in VND] (negative for a write-off). */
    @Query("""
            select m.adjustmentRequestId, sum(m.value) from StockMovement m
            where m.adjustmentRequestId in :requestIds
            group by m.adjustmentRequestId""")
    List<Object[]> sumValueByAdjustmentRequest(Collection<Long> requestIds);

    /** The movements an applied adjustment posted, by part and lot. */
    List<StockMovement> findByAdjustmentRequestIdOrderByProductIdAscIdAsc(Long adjustmentRequestId);

    /** The SALE_ISSUE movements of the order's part, latest first: where a corrected sale puts goods back. */
    @Query("""
            select m from StockMovement m
            where m.salesOrderId = :salesOrderId and m.productId = :productId
              and m.movementType = fu.hl.g64.motorflow.inventory.entity.enums.MovementType.SALE_ISSUE
            order by m.id desc""")
    List<StockMovement> findSaleIssues(long salesOrderId, long productId);
}
