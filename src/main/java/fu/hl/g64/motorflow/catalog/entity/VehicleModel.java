package fu.hl.g64.motorflow.catalog.entity;

import fu.hl.g64.motorflow.catalog.entity.enums.VehicleModelStatus;
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

/** A vehicle model used to search parts and to declare which parts fit it. */
@Entity
@Table(name = "vehicle_model")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VehicleModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String manufacturer;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 20)
    private VehicleType vehicleType;

    @Column(name = "year_from")
    private Short yearFrom;

    @Column(name = "year_to")
    private Short yearTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VehicleModelStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static VehicleModel create(String manufacturer, String modelName, VehicleType vehicleType, Short yearFrom,
                                      Short yearTo, LocalDateTime now) {
        VehicleModel model = new VehicleModel();
        model.status = VehicleModelStatus.ACTIVE;
        model.createdAt = now;
        model.update(manufacturer, modelName, vehicleType, yearFrom, yearTo, now);
        return model;
    }

    public void update(String newManufacturer, String newModelName, VehicleType newVehicleType, Short newYearFrom,
                       Short newYearTo, LocalDateTime now) {
        manufacturer = newManufacturer;
        modelName = newModelName;
        vehicleType = newVehicleType;
        yearFrom = newYearFrom;
        yearTo = newYearTo;
        updatedAt = now;
    }

    /** UC-25. Compatibility already declared with this model stays. */
    public void changeStatus(VehicleModelStatus newStatus, LocalDateTime now) {
        status = newStatus;
        updatedAt = now;
    }
}
