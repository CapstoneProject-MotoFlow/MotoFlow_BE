package fu.hl.g64.motorflow.inventory.helper;

import fu.hl.g64.motorflow.pricing.helper.PricingRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/** The UC-104 expiry warning setting, shared by the inventory screens and JOB-03. */
@Component
@RequiredArgsConstructor
public class ExpiryWarning {

    private final PricingRules rules;
    private final Clock clock;

    /** Days before expiry that a lot counts as near expiry, or null if the Owner has not set it. */
    public Integer daysInEffect() {
        return rules.expiryWarningDaysInEffect(LocalDateTime.now(clock));
    }
}
