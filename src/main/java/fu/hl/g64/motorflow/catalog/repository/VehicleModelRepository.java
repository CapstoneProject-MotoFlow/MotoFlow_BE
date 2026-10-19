package fu.hl.g64.motorflow.catalog.repository;

import fu.hl.g64.motorflow.catalog.entity.VehicleModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VehicleModelRepository extends JpaRepository<VehicleModel, Long> {

    /** Null years match null years, like the unique key of the table. */
    Optional<VehicleModel> findFirstByManufacturerIgnoreCaseAndModelNameIgnoreCaseAndYearFromAndYearTo(
            String manufacturer, String modelName, Short yearFrom, Short yearTo);
}
