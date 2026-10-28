package fu.hl.g64.motorflow.partner.repository;

import fu.hl.g64.motorflow.partner.entity.CreditEligibilityEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CreditEligibilityEventRepository extends JpaRepository<CreditEligibilityEvent, Long> {

    List<CreditEligibilityEvent> findByCustomerIdOrderByDecidedAtDescIdDesc(Long customerId);
}
