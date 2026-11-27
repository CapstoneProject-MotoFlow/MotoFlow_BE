package fu.hl.g64.motorflow.support;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

/**
 * Counts the SQL statements Hibernate prepares ({@link #prepared()}) and the statements actually executed on JDBC
 * ({@link #executed()}, including {@code JdbcTemplate} / {@code NamedParameterJdbcTemplate}), so tests can guard
 * against N+1 regressions and measure query budgets.
 */
public class QueryCounter {

    public enum Kind {
        SELECT, INSERT, UPDATE, DELETE, OTHER
    }

    private final Statistics statistics;
    private final SqlCountingDataSource dataSource;

    public QueryCounter(EntityManagerFactory entityManagerFactory, SqlCountingDataSource dataSource) {
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        this.dataSource = dataSource;
    }

    public void reset() {
        statistics.clear();
        dataSource.reset();
    }

    /** Statements prepared by Hibernate since the last {@link #reset()}. */
    public long prepared() {
        return statistics.getPrepareStatementCount();
    }

    /** Statements actually executed on JDBC since the last {@link #reset()}. */
    public long executed() {
        return dataSource.executed();
    }

    /** JDBC round-trips executed since the last {@link #reset()}. */
    public long roundTrips() {
        return dataSource.roundTrips();
    }

    /** Executed statements of a given kind since the last {@link #reset()}. */
    public long executed(Kind kind) {
        return dataSource.executed(kind);
    }

    /** Compact breakdown of executed statements since the last {@link #reset()}. */
    public String summary() {
        return dataSource.summary();
    }
}
