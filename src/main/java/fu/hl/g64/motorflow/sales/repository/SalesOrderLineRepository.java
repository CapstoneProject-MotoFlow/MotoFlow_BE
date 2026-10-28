package fu.hl.g64.motorflow.sales.repository;

import fu.hl.g64.motorflow.sales.entity.SalesOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface SalesOrderLineRepository extends JpaRepository<SalesOrderLine, Long> {

    /**
     * [salesOrderId, total in VND] from each order's current version: the delivered quantity once Branch Manager
     * has confirmed it, the requested quantity before.
     */
    @Query("""
            select v.salesOrderId, sum(coalesce(l.deliveredQty, l.requestedQty) * l.actualUnitPrice)
            from SalesOrderLine l join SalesOrderVersion v on v.id = l.salesOrderVersionId
            where v.salesOrderId in :orderIds
              and v.versionNo = (select max(w.versionNo) from SalesOrderVersion w where w.salesOrderId = v.salesOrderId)
            group by v.salesOrderId""")
    List<Object[]> currentTotals(Collection<Long> orderIds);
}
