package fu.hl.g64.motorflow.sales.repository;

import fu.hl.g64.motorflow.sales.entity.SalesOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
