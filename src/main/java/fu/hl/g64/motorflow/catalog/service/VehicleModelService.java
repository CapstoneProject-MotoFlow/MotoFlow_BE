package fu.hl.g64.motorflow.catalog.service;

import fu.hl.g64.motorflow.catalog.dto.VehicleModelRequest;
import fu.hl.g64.motorflow.catalog.dto.VehicleModelResponse;
import fu.hl.g64.motorflow.catalog.entity.VehicleModel;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleModelStatus;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleType;
import fu.hl.g64.motorflow.catalog.repository.VehicleModelRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** UC-22 View, UC-23 Create, UC-24 Update and UC-25 Deactivate Vehicle Model (BR-05). */
@Service
@RequiredArgsConstructor
public class VehicleModelService {

    private final VehicleModelRepository vehicleModels;
    private final AuditLogRepository auditLog;
    private final Clock clock;

    /**
     * By manufacturer, then model. Every filter is optional; {@code q} matches manufacturer or model name.
     * shortcut: filters in memory, fine for a few hundred models; move to a query if they reach thousands.
     */
    @Transactional(readOnly = true)
    public List<VehicleModelResponse> list(String q, VehicleType vehicleType, VehicleModelStatus status) {
        String needle = StringUtils.hasText(q) ? q.strip().toLowerCase(Locale.ROOT) : null;
        return vehicleModels.findAll(Sort.by("manufacturer", "modelName", "yearFrom")).stream()
                .filter(m -> vehicleType == null || m.getVehicleType() == vehicleType)
                .filter(m -> status == null || m.getStatus() == status)
                .filter(m -> needle == null || Stream.of(m.getManufacturer(), m.getModelName())
                        .anyMatch(text -> text.toLowerCase(Locale.ROOT).contains(needle)))
                .map(VehicleModelResponse::from)
                .toList();
    }

    @Transactional
    public VehicleModelResponse create(AuthenticatedUser actor, VehicleModelRequest request) {
        requireValid(null, request);
        LocalDateTime now = LocalDateTime.now(clock);
        VehicleModel model = save(VehicleModel.create(request.manufacturer().strip(), request.modelName().strip(),
                request.vehicleType(), request.yearFrom(), request.yearTo(), now), request);
        auditLog.save(AuditLog.record("VEHICLE_MODEL", model.getId(), "VEHICLE_MODEL_CREATED", actor.id(), null, now));
        return VehicleModelResponse.from(model);
    }

    @Transactional
    public VehicleModelResponse update(AuthenticatedUser actor, long id, VehicleModelRequest request) {
        VehicleModel model = find(id);
        requireValid(id, request);
        LocalDateTime now = LocalDateTime.now(clock);
        model.update(request.manufacturer().strip(), request.modelName().strip(), request.vehicleType(),
                request.yearFrom(), request.yearTo(), now);
        save(model, request);
        auditLog.save(AuditLog.record("VEHICLE_MODEL", id, "VEHICLE_MODEL_UPDATED", actor.id(), null, now));
        return VehicleModelResponse.from(model);
    }

    /** UC-25. Parts already declared compatible keep the link; new links need an active model. */
    @Transactional
    public void changeStatus(AuthenticatedUser actor, long id, VehicleModelStatus status) {
        VehicleModel model = find(id);
        if (model.getStatus() == status) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        model.changeStatus(status, now);
        String action = status == VehicleModelStatus.ACTIVE ? "VEHICLE_MODEL_ACTIVATED" : "VEHICLE_MODEL_DEACTIVATED";
        auditLog.save(AuditLog.record("VEHICLE_MODEL", id, action, actor.id(), null, now));
    }

    private void requireValid(Long selfId, VehicleModelRequest request) {
        if (request.yearFrom() != null && request.yearTo() != null && request.yearFrom() > request.yearTo()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG64");
        }
        vehicleModels.findFirstByManufacturerIgnoreCaseAndModelNameIgnoreCaseAndYearFromAndYearTo(
                        request.manufacturer().strip(), request.modelName().strip(), request.yearFrom(), request.yearTo())
                .filter(other -> !other.getId().equals(selfId))
                .ifPresent(other -> {
                    throw duplicate(request);
                });
    }

    private VehicleModel save(VehicleModel model, VehicleModelRequest request) {
        try {
            return vehicleModels.saveAndFlush(model);
        } catch (DataIntegrityViolationException e) {
            // Same model saved by someone else a moment earlier.
            throw duplicate(request);
        }
    }

    private VehicleModel find(long id) {
        return vehicleModels.findById(id).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG63"));
    }

    private static BusinessException duplicate(VehicleModelRequest request) {
        return new BusinessException(HttpStatus.CONFLICT, "MSG62", request.manufacturer().strip(), request.modelName().strip());
    }
}
