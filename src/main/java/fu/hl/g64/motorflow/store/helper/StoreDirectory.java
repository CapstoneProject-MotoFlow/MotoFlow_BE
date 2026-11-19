package fu.hl.g64.motorflow.store.helper;

import fu.hl.g64.motorflow.common.config.CacheConfig;
import fu.hl.g64.motorflow.store.dto.StoreResponse;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The store rows of the chain, cached (R4 III.2.1, "Stores"). Only the store's own columns are cached; its managers
 * and the number of parts in stock change with accounts and stock, so the store list adds them on every request.
 * {@code StoreServiceImpl} clears the cache when a store is created, copied, updated or given an Owner manager.
 */
@Component
@RequiredArgsConstructor
public class StoreDirectory {

    private final StoreRepository stores;

    /** Every store ordered by id, without managers and with 0 parts in stock. */
    @Cacheable(cacheNames = CacheConfig.STORES, key = "'all'")
    @Transactional(readOnly = true)
    public List<StoreResponse> all() {
        return stores.findAll(Sort.by("id")).stream()
                .map(store -> StoreResponse.from(store, List.of(), 0))
                .toList();
    }
}
