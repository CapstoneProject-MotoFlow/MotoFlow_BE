package fu.hl.g64.motorflow.catalog.entity;

import fu.hl.g64.motorflow.catalog.entity.enums.PartCategoryStatus;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleType;
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

/** A group of spare parts for one vehicle type, organised as a parent-child tree. */
@Entity
@Table(name = "part_category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_name", nullable = false, length = 100)
    private String categoryName;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 20)
    private VehicleType vehicleType;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PartCategoryStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static PartCategory create(String name, VehicleType vehicleType, Long parentId, String description,
                                      LocalDateTime now) {
        PartCategory category = new PartCategory();
        category.status = PartCategoryStatus.ACTIVE;
        category.createdAt = now;
        category.update(name, vehicleType, parentId, description, now);
        return category;
    }

    /** UC-20: rename, or move to another vehicle type or parent. */
    public void update(String newName, VehicleType newVehicleType, Long newParentId, String newDescription,
                       LocalDateTime now) {
        categoryName = newName;
        vehicleType = newVehicleType;
        parentId = newParentId;
        description = newDescription;
        updatedAt = now;
    }

    /** A sub-category follows its parent when the parent moves to another vehicle type. */
    public void changeVehicleType(VehicleType newVehicleType, LocalDateTime now) {
        vehicleType = newVehicleType;
        updatedAt = now;
    }

    /** UC-21. Parts already in the category keep it. */
    public void changeStatus(PartCategoryStatus newStatus, LocalDateTime now) {
        status = newStatus;
        updatedAt = now;
    }
}
