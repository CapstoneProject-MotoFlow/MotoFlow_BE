package fu.hl.g64.motorflow.store.service;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Warehouse;
import fu.hl.g64.motorflow.store.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** BR-03: which store's stock, prices and documents a user may see. */
@Component
@RequiredArgsConstructor
public class StoreScope {

    private final WarehouseRepository warehouses;

    /**
     * The warehouse of the requested store, or of the user's own store when none is requested.
     * Null when nothing is requested and the user has no store (Owner).
     */
    public Warehouse viewableWarehouse(AuthenticatedUser user, Long requestedStoreId) {
        if (requestedStoreId == null || requestedStoreId.equals(user.storeId())) {
            return user.storeId() == null ? null : warehouses.findByStoreId(user.storeId()).orElse(null);
        }
        if (!seesEveryStore(user)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        return warehouses.findByStoreId(requestedStoreId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG52"));
    }

    /** Owner, and the Branch Manager of the store holding the central warehouse, see every store (BR-03). */
    public boolean seesEveryStore(AuthenticatedUser user) {
        return user.role() == Role.OWNER
                || (user.role() == Role.BRANCH_MANAGER && user.storeId() != null
                && warehouses.findByStoreId(user.storeId()).map(Warehouse::isCentral).orElse(false));
    }
}
