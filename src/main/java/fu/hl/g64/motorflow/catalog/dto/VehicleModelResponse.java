package fu.hl.g64.motorflow.catalog.dto;

import fu.hl.g64.motorflow.catalog.entity.VehicleModel;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleModelStatus;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleType;

public record VehicleModelResponse(long id, String manufacturer, String modelName, VehicleType vehicleType,
                                   Short yearFrom, Short yearTo, VehicleModelStatus status) {

    public static VehicleModelResponse from(VehicleModel model) {
        return new VehicleModelResponse(model.getId(), model.getManufacturer(), model.getModelName(),
                model.getVehicleType(), model.getYearFrom(), model.getYearTo(), model.getStatus());
    }
}
