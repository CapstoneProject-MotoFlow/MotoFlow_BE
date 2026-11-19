package fu.hl.g64.motorflow.common.config;

import org.springframework.cache.Cache;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Callable;

/**
 * Wraps a cache so that puts go in at once and evictions run again when the surrounding transaction ends, whether it
 * commits or rolls back.
 *
 * <p>Spring's {@code TransactionAwareCacheDecorator} holds a reader's put until the reader's own transaction ends.
 * A reader that read the old rows before a writer committed could then put them back after the writer's eviction,
 * and they would stay until the entry expired. Putting at once means a reader's old rows are already in the cache
 * when the writer's transaction ends, so the eviction then removes them.
 */
final class AfterCompletionEvictingCache implements Cache {

    private final Cache target;

    AfterCompletionEvictingCache(Cache target) {
        this.target = target;
    }

    @Override
    public String getName() {
        return target.getName();
    }

    @Override
    public Object getNativeCache() {
        return target.getNativeCache();
    }

    @Override
    public ValueWrapper get(Object key) {
        return target.get(key);
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        return target.get(key, type);
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        return target.get(key, valueLoader);
    }

    @Override
    public void put(Object key, Object value) {
        target.put(key, value);
    }

    @Override
    public void evict(Object key) {
        target.evict(key);
        afterCompletion(() -> target.evict(key));
    }

    @Override
    public void clear() {
        target.clear();
        afterCompletion(target::clear);
    }

    // Also run after a rollback: puts made inside the transaction may hold rows that were never committed.
    private static void afterCompletion(Runnable eviction) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    eviction.run();
                }
            });
        }
    }
}
