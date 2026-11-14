package fu.hl.g64.motorflow.report.helper;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * The store and the days a report covers. A Branch Manager sees only their own store (MSG05 for another one); Owner
 * sees every store or the one chosen (MSG52 when it does not exist). The period runs from {@code first} to
 * {@code last}, both included: the current month up to today by default, at most 366 days (MSG264).
 */
public record ReportScope(Long storeId, LocalDate first, LocalDate last) {

    public static final long MAX_DAYS = 366;

    public static ReportScope of(AuthenticatedUser user, Long storeId, LocalDate from, LocalDate to, LocalDate today,
                                 StoreRepository stores) {
        if (user.role() != Role.OWNER) {
            if (storeId != null && !storeId.equals(user.storeId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
            }
            storeId = user.storeId();
        } else if (storeId != null && !stores.existsById(storeId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MSG52");
        }
        LocalDate last = to != null ? to : (from != null && from.isAfter(today) ? from : today);
        LocalDate first = from != null ? from : last.withDayOfMonth(1);
        if (first.isAfter(last) || ChronoUnit.DAYS.between(first, last) + 1 > MAX_DAYS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG264", MAX_DAYS);
        }
        return new ReportScope(storeId, first, last);
    }

    /** The first moment of the period. */
    public LocalDateTime start() {
        return first.atStartOfDay();
    }

    /** The first moment after the period. */
    public LocalDateTime end() {
        return last.plusDays(1).atStartOfDay();
    }
}
