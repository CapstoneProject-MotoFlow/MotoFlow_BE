package fu.hl.g64.motorflow.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * In-memory cache for catalog data that is read on most screens and rarely changed: the category tree, the
 * descendants of a category (used by every search filtered by category) and the vehicle model list.
 * Stock, prices, costs, debts and document statuses are never cached; they are always read from the database.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CATEGORY_TREE = "categoryTree";
    public static final String CATEGORY_DESCENDANTS = "categoryDescendants";
    public static final String VEHICLE_MODELS = "vehicleModels";

    @Bean
    public CacheManager cacheManager(@Value("${motoflow.cache.spec}") String spec) {
        CaffeineCacheManager caffeine = new CaffeineCacheManager(CATEGORY_TREE, CATEGORY_DESCENDANTS, VEHICLE_MODELS);
        caffeine.setCaffeine(Caffeine.from(spec));
        caffeine.setAllowNullValues(false);
        // An eviction inside a write transaction waits for the commit, so a reader cannot put the old rows back
        // into the cache between the eviction and the commit.
        return new TransactionAwareCacheManagerProxy(caffeine);
    }
}
