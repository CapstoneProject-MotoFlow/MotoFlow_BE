package fu.hl.g64.motorflow.support;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

/**
 * Counts the SQL statements Hibernate prepares (repositories and JPQL; not JdbcTemplate), so a test can show that a
 * list or a detail runs the same number of queries for 1 row as for 5: no N+1.
 */
public class QueryCounter {

    private final Statistics statistics;

    public QueryCounter(EntityManagerFactory entityManagerFactory) {
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    public void reset() {
        statistics.clear();
    }

    /** Statements prepared since the last {@link #reset()}. */
    public long prepared() {
        return statistics.getPrepareStatementCount();
    }
}
