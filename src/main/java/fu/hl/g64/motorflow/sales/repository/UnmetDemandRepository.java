package fu.hl.g64.motorflow.sales.repository;

import fu.hl.g64.motorflow.sales.entity.UnmetDemand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface UnmetDemandRepository extends JpaRepository<UnmetDemand, Long> {

    /** Still waiting, from orders whose current version is for the customer; every store when {@code storeId} is null. */
    @Query("""
            select u from UnmetDemand u
            where u.status = fu.hl.g64.motorflow.sales.entity.enums.UnmetDemandStatus.WAITING
              and u.salesOrderId in (select o.id from SalesOrder o
                      where (:storeId is null or o.storeId = :storeId)
                        and exists (select v.id from SalesOrderVersion v
                                    where v.salesOrderId = o.id and v.customerId = :customerId
                                      and v.versionNo = (select max(w.versionNo) from SalesOrderVersion w
                                                         where w.salesOrderId = o.id)))
            order by u.recordedAt desc, u.id desc""")
    List<UnmetDemand> findWaitingByCustomer(Long customerId, Long storeId);
}
