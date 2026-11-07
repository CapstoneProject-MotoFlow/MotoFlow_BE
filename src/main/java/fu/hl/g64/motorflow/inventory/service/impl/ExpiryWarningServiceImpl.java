package fu.hl.g64.motorflow.inventory.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.dto.ExpiryWarningRequest;
import fu.hl.g64.motorflow.inventory.dto.ExpiryWarningResponse;
import fu.hl.g64.motorflow.inventory.dto.ExpiryWarningResponse.Version;
import fu.hl.g64.motorflow.inventory.service.ExpiryWarningService;
import fu.hl.g64.motorflow.pricing.entity.PricingPolicy;
import fu.hl.g64.motorflow.pricing.entity.enums.PolicyType;
import fu.hl.g64.motorflow.pricing.helper.PricingVersions;
import fu.hl.g64.motorflow.pricing.repository.PricingPolicyRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExpiryWarningServiceImpl implements ExpiryWarningService {

    private static final PolicyType TYPE = PolicyType.EXPIRY_WARNING;
    /** Same entity type as the other pricing_policy saves, whose constant is not visible outside the pricing module. */
    private static final String AUDIT_ENTITY = "PRICING_POLICY";
    private static final int HISTORY_SIZE = 20;
    private static final int LAST_YEAR = 9999;

    private final PricingPolicyRepository policies;
    private final AuditLogRepository auditLog;
    private final UserAccountRepository users;
    private final Clock clock;

    // UC-104
    @Transactional(readOnly = true)
    @Override
    public ExpiryWarningResponse expiryWarning() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<PricingPolicy> versions = policies.findChainVersions(TYPE);
        Map<Long, String> names = users.findAllById(versions.stream().map(PricingPolicy::getSetBy)
                        .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        Function<PricingPolicy, Version> toVersion = v -> new Version(v.getExpiryWarningDays(), v.getEffectiveFrom(),
                v.getSetBy(), names.get(v.getSetBy()));
        Version inEffect = versions.stream().filter(v -> !v.isPending(now)).findFirst().map(toVersion).orElse(null);
        // Newest first, so the last version still waiting is the next one to apply.
        Version waiting = versions.stream().filter(v -> v.isPending(now)).reduce((newer, older) -> older)
                .map(toVersion).orElse(null);
        return new ExpiryWarningResponse(inEffect, waiting, versions.stream().limit(HISTORY_SIZE).map(toVersion).toList());
    }

    // UC-104
    @Transactional
    @Override
    public ExpiryWarningResponse setExpiryWarning(AuthenticatedUser owner, ExpiryWarningRequest request) {
        // A number sent as the date is read as days since 1970: a year so far off that the database refuses it.
        if (request.effectiveDate().getYear() > LAST_YEAR) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG02");
        }
        policies.lockSetting(TYPE.name());
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from = PricingVersions.effectiveFrom(request.effectiveDate(), now);
        List<PricingPolicy> replaced = PricingVersions.replacedBy(from, policies.findChainVersions(TYPE), now);
        if (!replaced.isEmpty()) {
            // Deleted at once, before the insert, because the new version may take the same date.
            policies.deleteAllInBatch(replaced);
        }
        PricingPolicy saved = policies.save(PricingPolicy.expiryWarning(request.days(), from, owner.id(), now));

        Map<String, Object> oldData = replaced.isEmpty() ? null : Map.of("replaced_scheduled", replaced.stream()
                .map(v -> Map.<String, Object>of("days", v.getExpiryWarningDays(),
                        "effective_from", v.getEffectiveFrom().toString()))
                .toList());
        auditLog.save(AuditLog.record(AUDIT_ENTITY, saved.getId(), TYPE.name() + "_SET", owner.id(), null, oldData,
                Map.of("days", saved.getExpiryWarningDays(), "effective_from", from.toString()), now));
        return expiryWarning();
    }
}
