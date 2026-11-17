package fu.hl.g64.motorflow.inventory.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Sums of the stock ledger for one inventory period, by warehouse and part (BR-85). A movement belongs to the period
 * it was posted into (a count adjustment of the previous period may be posted after the month ended, BR-83).
 */
@Component
public class PeriodLedgerQuery {

    /** Sums for one warehouse and part; {@code movementType} is null for opening and in-transit sums. */
    public record Row(long storeId, long productId, String movementType, long inQty, long inValue, long outQty,
                      long outValue) {
    }

    /** Stands for "every warehouse" in {@code store_id in (:storeIds)}, which must not be empty. */
    private static final List<Long> NO_STORE = List.of(-1L);

    private final EntityManager em;

    public PeriodLedgerQuery(EntityManager em) {
        this.em = em;
    }

    /**
     * Opening quantity and value of each warehouse and part: the closing balances of {@code basePeriodId} (the latest
     * closed period before this one, or null) plus every movement of the periods between it and {@code month}.
     * Returned in {@code inQty} and {@code inValue}.
     */
    public List<Row> opening(Long basePeriodId, LocalDate baseMonth, LocalDate month, Collection<Long> storeIds) {
        return rows(em.createNativeQuery("""
                        select store_id, product_id, cast(null as varchar), sum(qty), sum(val), 0, 0 from (
                            select store_id, product_id, closing_qty as qty, closing_value as val
                            from period_stock_balance where inventory_period_id = cast(:basePeriodId as bigint)
                            union all
                            select m.store_id, m.product_id, m.quantity_change, m.value
                            from stock_movement m join inventory_period p on p.id = m.inventory_period_id
                            where p.period_month > :baseMonth and p.period_month < :month
                        ) x
                        where :allStores or store_id in (:storeIds)
                        group by store_id, product_id""")
                .setParameter("basePeriodId", basePeriodId)
                .setParameter("baseMonth", baseMonth)
                .setParameter("month", month)
                .setParameter("allStores", storeIds == null)
                .setParameter("storeIds", storeIds == null ? NO_STORE : storeIds)
                .getResultList());
    }

    /**
     * What came in and went out of each warehouse in the period, by part and movement type. The movements of one
     * document, warehouse, part and type are netted first, so a change of status inside a warehouse (to damaged, or
     * received out of transit) is neither in nor out. A net quantity of zero with a value (a price correction) counts
     * its value only.
     */
    public List<Row> flows(long periodId, Collection<Long> storeIds) {
        return rows(em.createNativeQuery("""
                        with g as (
                            select store_id, product_id, movement_type, sum(quantity_change) as q, sum(value) as v
                            from stock_movement
                            where inventory_period_id = :periodId and (:allStores or store_id in (:storeIds))
                            group by store_id, product_id, movement_type, sales_order_id, goods_receipt_note_id,
                                     transfer_order_id, discrepancy_resolution_event_id, adjustment_request_id,
                                     stock_count_id
                        )
                        select store_id, product_id, movement_type,
                               sum(case when q > 0 or (q = 0 and v > 0) then q else 0 end),
                               sum(case when q > 0 or (q = 0 and v > 0) then v else 0 end),
                               sum(case when q < 0 or (q = 0 and v < 0) then -q else 0 end),
                               sum(case when q < 0 or (q = 0 and v < 0) then -v else 0 end)
                        from g
                        group by store_id, product_id, movement_type""")
                .setParameter("periodId", periodId)
                .setParameter("allStores", storeIds == null)
                .setParameter("storeIds", storeIds == null ? NO_STORE : storeIds)
                .getResultList());
    }

    /**
     * Goods on their way to each warehouse at the end of {@code month}, in {@code inQty} and {@code inValue}.
     * They are posted at the destination when dispatched, so they are part of its closing balance.
     */
    public List<Row> inTransit(LocalDate month, Collection<Long> storeIds) {
        return rows(em.createNativeQuery("""
                        select m.store_id, m.product_id, cast(null as varchar), sum(m.quantity_change), sum(m.value),
                               0, 0
                        from stock_movement m join inventory_period p on p.id = m.inventory_period_id
                        where m.stock_status = 'IN_TRANSIT' and p.period_month <= :month
                          and (:allStores or m.store_id in (:storeIds))
                        group by m.store_id, m.product_id
                        having sum(m.quantity_change) <> 0 or sum(m.value) <> 0""")
                .setParameter("month", month)
                .setParameter("allStores", storeIds == null)
                .setParameter("storeIds", storeIds == null ? NO_STORE : storeIds)
                .getResultList());
    }

    @SuppressWarnings("unchecked")
    private static List<Row> rows(List<?> result) {
        return ((List<Object[]>) result).stream().map(r -> new Row(
                ((Number) r[0]).longValue(), ((Number) r[1]).longValue(), (String) r[2],
                ((Number) r[3]).longValue(), ((Number) r[4]).longValue(),
                ((Number) r[5]).longValue(), ((Number) r[6]).longValue())).toList();
    }
}
