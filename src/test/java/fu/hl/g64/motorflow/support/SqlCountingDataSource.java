package fu.hl.g64.motorflow.support;

import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.sql.DataSource;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps the test {@link DataSource} with {@code datasource-proxy} so {@link QueryCounter} counts every SQL statement
 * actually executed on JDBC (Hibernate, Spring Data JPA, and {@code JdbcTemplate} / {@code NamedParameterJdbcTemplate}).
 */
public class SqlCountingDataSource implements BeanPostProcessor {

    private final Map<QueryCounter.Kind, AtomicLong> counts = new EnumMap<>(QueryCounter.Kind.class);
    private final AtomicLong roundTrips = new AtomicLong();

    public SqlCountingDataSource() {
        for (QueryCounter.Kind kind : QueryCounter.Kind.values()) {
            counts.put(kind, new AtomicLong());
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource dataSource && !(bean instanceof ProxyDataSource)) {
            return ProxyDataSourceBuilder.create(dataSource)
                    .name("sql-counting")
                    .listener(new QueryExecutionListener() {
                        @Override
                        public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
                        }

                        @Override
                        public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
                            record(execInfo, queryInfoList);
                        }
                    })
                    .build();
        }
        return bean;
    }

    private void record(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        roundTrips.incrementAndGet();
        for (QueryInfo info : queryInfoList) {
            long statements = execInfo.isBatch() ? Math.max(1, info.getParametersList().size()) : 1;
            counts.get(classify(info.getQuery())).addAndGet(statements);
        }
    }

    public void reset() {
        counts.values().forEach(counter -> counter.set(0));
        roundTrips.set(0);
    }

    public long roundTrips() {
        return roundTrips.get();
    }

    public long executed() {
        long total = 0;
        for (AtomicLong counter : counts.values()) {
            total += counter.get();
        }
        return total;
    }

    public long executed(QueryCounter.Kind kind) {
        return counts.get(kind).get();
    }

    public String summary() {
        long other = executed(QueryCounter.Kind.OTHER);
        String base = "select=%d, insert=%d, update=%d, delete=%d".formatted(
                executed(QueryCounter.Kind.SELECT),
                executed(QueryCounter.Kind.INSERT),
                executed(QueryCounter.Kind.UPDATE),
                executed(QueryCounter.Kind.DELETE));
        String full = other > 0 ? base + ", other=" + other : base;
        return full + ", roundTrips=" + roundTrips();
    }

    static QueryCounter.Kind classify(String sql) {
        if (sql == null) {
            return QueryCounter.Kind.OTHER;
        }
        String keyword = leadingKeyword(sql);
        return switch (keyword) {
            case "SELECT", "WITH" -> QueryCounter.Kind.SELECT;
            case "INSERT" -> QueryCounter.Kind.INSERT;
            case "UPDATE" -> QueryCounter.Kind.UPDATE;
            case "DELETE" -> QueryCounter.Kind.DELETE;
            default -> QueryCounter.Kind.OTHER;
        };
    }

    private static String leadingKeyword(String sql) {
        int i = 0;
        int len = sql.length();
        while (i < len) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i + 2);
                i = end < 0 ? len : end + 1;
            } else if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? len : end + 2;
            } else {
                break;
            }
        }
        int start = i;
        while (i < len && Character.isLetter(sql.charAt(i))) {
            i++;
        }
        return sql.substring(start, i).toUpperCase(Locale.ROOT);
    }
}
