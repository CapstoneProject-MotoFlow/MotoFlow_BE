package fu.hl.g64.motorflow.catalog.service;

import fu.hl.g64.motorflow.catalog.dto.VehicleModelRequest;
import fu.hl.g64.motorflow.catalog.dto.VehicleModelResponse;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleModelStatus;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleType;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.util.List;

/** UC-22 View, UC-23 Create, UC-24 Update and UC-25 Deactivate Vehicle Model (BR-05). */
public interface VehicleModelService {
    /**
     * By manufacturer, then model. Every filter is optional; {@code q} matches manufacturer or model name.
     * shortcut: filters in memory, fine for a few hundred models; move to a query if they reach thousands.
     */
    List<VehicleModelResponse> list(String q, VehicleType vehicleType, VehicleModelStatus status);

    VehicleModelResponse get(long id);

    VehicleModelResponse create(AuthenticatedUser actor, VehicleModelRequest request);

    VehicleModelResponse update(AuthenticatedUser actor, long id, VehicleModelRequest request);

    /** UC-25. Parts already declared compatible keep the link; new links need an active model. */
    void changeStatus(AuthenticatedUser actor, long id, VehicleModelStatus status);
}
