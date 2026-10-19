package fu.hl.g64.motorflow.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Compatibility between parts and vehicle models. */
@Entity
@Table(name = "product_vehicle_model")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductVehicleModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "vehicle_model_id", nullable = false)
    private Long vehicleModelId;

    public static ProductVehicleModel of(Long productId, Long vehicleModelId) {
        ProductVehicleModel link = new ProductVehicleModel();
        link.productId = productId;
        link.vehicleModelId = vehicleModelId;
        return link;
    }
}
