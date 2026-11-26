package fu.hl.g64.motorflow.common.error;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

/**
 * Inspects {@link DataIntegrityViolationException} cause chains, including {@link SQLException#getNextException()}
 * chained onto {@link java.sql.BatchUpdateException} when JDBC batching is enabled.
 */
public final class DataIntegrityExceptions {

    private DataIntegrityExceptions() {
    }

    /**
     * Returns whether {@code constraintName} appears anywhere in {@code exception}'s cause chain, suppressed
     * exceptions, or chained {@link SQLException#getNextException()} entries.
     */
    public static boolean mentionsConstraint(DataIntegrityViolationException exception, String constraintName) {
        if (exception == null || constraintName == null || constraintName.isEmpty()) {
            return false;
        }
        String needle = constraintName.toLowerCase(Locale.ROOT);
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> queue = new ArrayDeque<>();
        queue.add(exception);
        while (!queue.isEmpty()) {
            Throwable current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof ConstraintViolationException cve && cve.getConstraintName() != null
                    && cve.getConstraintName().equalsIgnoreCase(constraintName)) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
            if (current.getCause() != null) {
                queue.add(current.getCause());
            }
            if (current instanceof SQLException sqlEx && sqlEx.getNextException() != null) {
                // JDBC BatchUpdateException attaches per-statement PSQLException details on getNextException(), not getCause().
                queue.add(sqlEx.getNextException());
            }
            for (Throwable suppressed : current.getSuppressed()) {
                queue.add(suppressed);
            }
        }
        return false;
    }
}
