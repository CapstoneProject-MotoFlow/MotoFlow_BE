package fu.hl.g64.motorflow.receivable.repository;

import fu.hl.g64.motorflow.receivable.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * Payments and refunds of the customer's orders, and payments of the customer's debts;
     * at one store, or at every store when {@code storeId} is null.
     */
    @Query("""
            select p from Payment p
            where p.salesOrderId in (select o.id from SalesOrder o
                      where o.customerId = :customerId and (:storeId is null or o.storeId = :storeId))
               or p.id in (select a.paymentId from PaymentAllocation a join Receivable r on r.id = a.receivableId
                      where r.customerId = :customerId and (:storeId is null or r.storeId = :storeId))""")
    Page<Payment> findByCustomer(Long customerId, Long storeId, Pageable pageable);

    /** Payments and refunds recorded at the store that nobody has verified or rejected yet. */
    @Query("""
            select count(p) from Payment p
            where p.status = fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus.UNVERIFIED
              and (p.salesOrderId in (select o.id from SalesOrder o where o.storeId = :storeId)
                   or p.id in (select a.paymentId from PaymentAllocation a join Receivable r on r.id = a.receivableId
                               where r.storeId = :storeId))""")
    long countUnverifiedAtStore(Long storeId);
}
