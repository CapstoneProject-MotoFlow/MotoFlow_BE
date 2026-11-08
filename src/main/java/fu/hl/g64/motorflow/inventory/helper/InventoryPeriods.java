package fu.hl.g64.motorflow.inventory.helper;

import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.entity.InventoryPeriod;
import fu.hl.g64.motorflow.inventory.repository.InventoryPeriodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/** Inventory periods are calendar months, opened on first use and chained to the period before. */
@Component
@RequiredArgsConstructor
public class InventoryPeriods {

    private final InventoryPeriodRepository periods;

    /** The open period of {@code date}'s month, opened if it does not exist yet; MSG85 if it is closed. */
    public InventoryPeriod openFor(LocalDate date) {
        openIfMissing(date);
        InventoryPeriod period = findFor(date).orElseThrow();
        if (!period.isOpen()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG85",
                    period.getPeriodMonth().format(DateTimeFormatter.ofPattern("MM/yyyy")));
        }
        return period;
    }

    /** Opens {@code date}'s month as a period if it does not exist yet; safe when two transactions do it at once. */
    public void openIfMissing(LocalDate date) {
        LocalDate month = date.withDayOfMonth(1);
        periods.openIfMissing(month, month.plusMonths(1).minusDays(1));
    }

    /** The period of {@code date}'s month, open or closed, if it exists. */
    public Optional<InventoryPeriod> findFor(LocalDate date) {
        return periods.findByPeriodMonth(date.withDayOfMonth(1));
    }
}
