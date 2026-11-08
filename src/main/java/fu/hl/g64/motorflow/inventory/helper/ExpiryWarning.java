package fu.hl.g64.motorflow.inventory.helper;

import fu.hl.g64.motorflow.pricing.entity.PricingPolicy;
import fu.hl.g64.motorflow.pricing.repository.PricingPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/** The UC-104 expiry warning setting, shared by the inventory screens and JOB-03. */
@Component
@RequiredArgsConstructor
public class ExpiryWarning {

    private final PricingPolicyRepository policies;
    private final Clock clock;

    /** Days before expiry that a lot counts as near expiry, or null if the Owner has not set it. */
    public Integer daysInEffect() {
        return policies.findExpiryWarningsInEffect(LocalDateTime.now(clock)).stream()
                .findFirst().map(PricingPolicy::getExpiryWarningDays).orElse(null);
    }
}
