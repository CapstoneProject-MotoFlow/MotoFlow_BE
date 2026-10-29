package fu.hl.g64.motorflow.receivable.repository;

import fu.hl.g64.motorflow.receivable.entity.PaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, Long> {

    List<PaymentAllocation> findByPaymentIdIn(Collection<Long> paymentIds);
}
