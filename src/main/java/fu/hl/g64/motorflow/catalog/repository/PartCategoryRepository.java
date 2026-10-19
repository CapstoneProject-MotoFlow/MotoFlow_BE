package fu.hl.g64.motorflow.catalog.repository;

import fu.hl.g64.motorflow.catalog.entity.PartCategory;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PartCategoryRepository extends JpaRepository<PartCategory, Long> {

    boolean existsByParentId(Long parentId);

    /** A null {@code parentId} matches top-level categories. */
    Optional<PartCategory> findFirstByVehicleTypeAndParentIdAndCategoryNameIgnoreCase(VehicleType vehicleType,
                                                                                      Long parentId, String name);
}
