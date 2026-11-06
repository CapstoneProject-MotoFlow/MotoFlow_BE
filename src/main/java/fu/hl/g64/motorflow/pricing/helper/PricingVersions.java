package fu.hl.g64.motorflow.pricing.helper;

import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.pricing.entity.PricingPolicy;
import fu.hl.g64.motorflow.pricing.entity.enums.PricingPolicyStatus;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Effective dates of pricing versions (BR-09, BR-11): from today on, one version in effect at a time. */
public final class PricingVersions {

    private PricingVersions() {
    }

    /**
     * When a version chosen to apply from {@code date} starts: right away for today, at the start of a later day.
     * MSG125 for a day before today.
     */
    public static LocalDateTime effectiveFrom(LocalDate date, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        if (date.isBefore(today)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG125");
        }
        // The database keeps microseconds; cut the rest so the stored time is never after "now".
        return date.equals(today) ? now.truncatedTo(ChronoUnit.MICROS) : date.atStartOfDay();
    }

    /**
     * The versions that a new version from {@code effectiveFrom} replaces. A setting waits for at most one version:
     * a new version for a later day replaces the one waiting, while one that applies today leaves it in place.
     */
    public static List<PricingPolicy> replacedBy(LocalDateTime effectiveFrom, List<PricingPolicy> versions,
                                                 LocalDateTime now) {
        return effectiveFrom.isAfter(now) ? versions.stream().filter(v -> v.isPending(now)).toList() : List.of();
    }

    /**
     * Maps versions of one setting, newest first, with the time each one ends (when the next one starts) and its
     * place in the Scheduled, Active, Superseded lifecycle, which follows from the dates.
     */
    public static <T> List<T> timeline(List<PricingPolicy> newestFirst, LocalDateTime now, Row<T> row) {
        List<T> result = new ArrayList<>();
        boolean currentSeen = false;
        for (int i = 0; i < newestFirst.size(); i++) {
            PricingPolicy version = newestFirst.get(i);
            LocalDateTime endsAt = i == 0 ? null : newestFirst.get(i - 1).getEffectiveFrom();
            PricingPolicyStatus state;
            if (version.isPending(now)) {
                state = PricingPolicyStatus.SCHEDULED;
            } else if (!currentSeen) {
                state = PricingPolicyStatus.ACTIVE;
                currentSeen = true;
            } else {
                state = PricingPolicyStatus.SUPERSEDED;
            }
            result.add(row.map(version, endsAt, state));
        }
        return result;
    }

    /** Builds one history row from a version, when it ends (null while open) and its state. */
    @FunctionalInterface
    public interface Row<T> {
        T map(PricingPolicy version, LocalDateTime effectiveTo, PricingPolicyStatus state);
    }
}
