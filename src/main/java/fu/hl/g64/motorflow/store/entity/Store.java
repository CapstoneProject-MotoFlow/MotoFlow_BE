package fu.hl.g64.motorflow.store.entity;

import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** A branch of the chain (UC-03 View Store List, UC-04 Create Store, UC-06 Update Store). */
@Entity
@Table(name = "store")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_name", nullable = false, unique = true, length = 150)
    private String storeName;

    @Column(nullable = false, length = 255)
    private String address;

    @Column(length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StoreStatus status;

    @Column(name = "copied_from_store_id")
    private Long copiedFromStoreId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** UC-04: a new store starts as planned, with no stock, documents or debts (BR-64). */
    public static Store create(String storeName, String address, String phone, LocalDateTime now) {
        Store store = new Store();
        store.storeName = storeName;
        store.address = address;
        store.phone = phone;
        store.status = StoreStatus.PLANNED;
        store.createdAt = now;
        store.updatedAt = now;
        return store;
    }

    /** UC-05: a new planned store that keeps which store it was copied from; only configuration is copied. */
    public static Store copyOf(Store template, String storeName, String address, String phone, LocalDateTime now) {
        Store store = create(storeName, address, phone, now);
        store.copiedFromStoreId = template.getId();
        return store;
    }

    /** UC-06. The id never changes, so documents and permission history keep pointing at the same store. */
    public void update(String newName, String newAddress, String newPhone, StoreStatus newStatus, LocalDateTime now) {
        storeName = newName;
        address = newAddress;
        phone = newPhone;
        status = newStatus;
        updatedAt = now;
    }

    /** Staff can be assigned to a store being prepared or open, not to a closed one. */
    public boolean acceptsStaff() {
        return status != StoreStatus.INACTIVE;
    }
}
