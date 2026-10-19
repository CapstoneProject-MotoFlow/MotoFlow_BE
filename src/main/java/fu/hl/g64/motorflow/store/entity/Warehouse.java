package fu.hl.g64.motorflow.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** The single warehouse of a store (BR-04). All stock figures belong to a warehouse. */
@Entity
@Table(name = "warehouse")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false, unique = true)
    private Long storeId;

    @Column(name = "warehouse_name", nullable = false, length = 150)
    private String warehouseName;

    @Column
    private String address;

    @Column(length = 20)
    private String phone;

    @Column(name = "is_central", nullable = false)
    private boolean isCentral;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** UC-04: every new store gets exactly one warehouse, created with it (BR-04). */
    public static Warehouse create(Long storeId, String name, String address, String phone, boolean central,
                                   LocalDateTime now) {
        Warehouse warehouse = new Warehouse();
        warehouse.storeId = storeId;
        warehouse.warehouseName = name;
        warehouse.address = address;
        warehouse.phone = phone;
        warehouse.isCentral = central;
        warehouse.createdAt = now;
        warehouse.updatedAt = now;
        return warehouse;
    }

    public void update(String newName, String newAddress, String newPhone, LocalDateTime now) {
        warehouseName = newName;
        address = newAddress;
        phone = newPhone;
        updatedAt = now;
    }

    /** Only while the chain has no central warehouse yet; it is never moved or removed (BR-04). */
    public void makeCentral(LocalDateTime now) {
        isCentral = true;
        updatedAt = now;
    }
}
