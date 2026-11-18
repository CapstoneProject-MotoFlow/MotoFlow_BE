package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.InventoryPeriod;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** UC-100: what stands in the way of closing a period (BR-84), or only deserves a look before it. */
@Component
public class PeriodClosingQuery {

    /** A count dated in the period that is not finished: it blocks closing. */
    public record OpenCount(long id, String storeName, String countType, LocalDate countDate, String status) {
    }

    /** A receipt that arrived by the end of the period and is not confirmed yet: a warning. */
    public record OpenReceipt(long id, String storeName, String receiptType, LocalDateTime arrivedAt, String status) {
    }

    /** A transfer dispatched by the end of the period and not closed yet: a warning. */
    public record OpenTransfer(long id, String sourceStoreName, String destStoreName, LocalDateTime dispatchedAt,
                               String status) {
    }

    private final EntityManager em;

    public PeriodClosingQuery(EntityManager em) {
        this.em = em;
    }

    /** Counts dated in the period (or posting into it) that are being prepared, counted or awaiting approval. */
    public List<OpenCount> openCounts(InventoryPeriod period) {
        List<?> rows = em.createNativeQuery("""
                        select c.id, s.store_name, c.count_type, c.count_date, c.status
                        from stock_count c join store s on s.id = c.store_id
                        where c.status in ('DRAFT', 'COUNTING', 'AWAITING_APPROVAL')
                          and (c.inventory_period_id = :periodId or c.count_date between :fromDate and :toDate)
                        order by c.count_date, c.id""")
                .setParameter("periodId", period.getId())
                .setParameter("fromDate", period.getFromDate())
                .setParameter("toDate", period.getToDate())
                .getResultList();
        return rows.stream().map(o -> (Object[]) o).map(r -> new OpenCount(((Number) r[0]).longValue(),
                (String) r[1], (String) r[2], date(r[3]), (String) r[4])).toList();
    }

    public List<OpenReceipt> openReceipts(InventoryPeriod period) {
        List<?> rows = em.createNativeQuery("""
                        select g.id, s.store_name, g.receipt_type, g.arrived_at, g.status
                        from goods_receipt_note g
                        left join purchase_order po on po.id = g.purchase_order_id
                        left join transfer_order t on t.id = g.transfer_order_id
                        join store s on s.id = coalesce(po.store_id,
                            case when g.receipt_type = 'RETURN_TO_SOURCE' then t.source_store_id
                                 else t.dest_store_id end)
                        where g.status <> 'CONFIRMED' and g.arrived_at < :end
                        order by g.arrived_at, g.id""")
                .setParameter("end", period.getToDate().plusDays(1).atStartOfDay())
                .getResultList();
        return rows.stream().map(o -> (Object[]) o).map(r -> new OpenReceipt(((Number) r[0]).longValue(),
                (String) r[1], (String) r[2], time(r[3]), (String) r[4])).toList();
    }

    public List<OpenTransfer> openTransfers(InventoryPeriod period) {
        List<?> rows = em.createNativeQuery("""
                        select t.id, src.store_name, dst.store_name, t.dispatched_at, t.status
                        from transfer_order t
                        join store src on src.id = t.source_store_id
                        join store dst on dst.id = t.dest_store_id
                        where t.status in ('IN_TRANSIT', 'RECONCILING') and t.dispatched_at < :end
                        order by t.dispatched_at, t.id""")
                .setParameter("end", period.getToDate().plusDays(1).atStartOfDay())
                .getResultList();
        return rows.stream().map(o -> (Object[]) o).map(r -> new OpenTransfer(((Number) r[0]).longValue(),
                (String) r[1], (String) r[2], time(r[3]), (String) r[4])).toList();
    }

    /** Native queries return java.time values under Hibernate 7, java.sql ones under some drivers. */
    private static LocalDate date(Object value) {
        return value instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) value;
    }

    private static LocalDateTime time(Object value) {
        return value instanceof Timestamp t ? t.toLocalDateTime() : (LocalDateTime) value;
    }
}
