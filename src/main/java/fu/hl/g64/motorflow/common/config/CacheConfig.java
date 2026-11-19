package fu.hl.g64.motorflow.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache (R4 III.2.1) for reference data that most screens read and few users change. Stock, lots,
 * the retail price of each part, costs, debts and document statuses are never cached: they are always read from
 * the database.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CATEGORY_TREE = "categoryTree";
    public static final String CATEGORY_DESCENDANTS = "categoryDescendants";
    public static final String VEHICLE_MODELS = "vehicleModels";
    public static final String STORES = "stores";
    public static final String PRICING_RULES = "pricingRules";

    @Bean
    public CacheManager cacheManager(@Value("${motoflow.cache.spec}") String spec) {
        CaffeineCacheManager caffeine = new CaffeineCacheManager(
                CATEGORY_TREE, CATEGORY_DESCENDANTS, VEHICLE_MODELS, STORES, PRICING_RULES);
        caffeine.setCaffeine(Caffeine.from(spec));
        caffeine.setAllowNullValues(false);
        // An eviction inside a write transaction runs again when the transaction ends (AfterCompletionEvictingCache).
        Map<String, Cache> wrapped = new ConcurrentHashMap<>();
        return new CacheManager() {
            @Override
            public Cache getCache(String name) {
                Cache cache = caffeine.getCache(name);
                return cache == null ? null : wrapped.computeIfAbsent(name, n -> new AfterCompletionEvictingCache(cache));
            }

            @Override
            public Collection<String> getCacheNames() {
                return caffeine.getCacheNames();
            }
        };
    }
}
