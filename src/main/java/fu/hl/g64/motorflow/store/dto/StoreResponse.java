package fu.hl.g64.motorflow.store.dto;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.Warehouse;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A row of SCR-06. {@code warehouse} is null only for stores created before BR-04 was enforced;
 * {@code managers} is empty when the store has no active Branch Manager ("Chưa gán");
 * {@code copiedFromStoreId} is the template store when the store was created by copying one (UC-05).
 */
public record StoreResponse(long id, String storeName, String address, String phone,
                            StoreStatus status, WarehouseInfo warehouse, List<ManagerInfo> managers,
                            long productsInStock, Long copiedFromStoreId,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {

    public record WarehouseInfo(long id, String name, boolean central) {
    }

    public record ManagerInfo(long id, String fullName, String phone) {
    }

    public static StoreResponse from(Store store, Warehouse warehouse, List<UserAccount> managers, long productsInStock) {
        return new StoreResponse(store.getId(), store.getStoreName(), store.getAddress(),
                store.getPhone(), store.getStatus(),
                warehouse == null ? null : new WarehouseInfo(warehouse.getId(), warehouse.getWarehouseName(), warehouse.isCentral()),
                managers.stream().map(m -> new ManagerInfo(m.getId(), m.getFullName(), m.getPhone())).toList(),
                productsInStock, store.getCopiedFromStoreId(), store.getCreatedAt(), store.getUpdatedAt());
    }
}
