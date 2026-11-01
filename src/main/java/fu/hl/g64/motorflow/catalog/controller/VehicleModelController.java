package fu.hl.g64.motorflow.catalog.controller;

import fu.hl.g64.motorflow.catalog.dto.VehicleModelRequest;
import fu.hl.g64.motorflow.catalog.dto.VehicleModelResponse;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleModelStatus;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleType;
import fu.hl.g64.motorflow.catalog.service.VehicleModelService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@Tag(name = "Vehicle models", description = "UC-22 to UC-25 (Owner, Branch Manager with catalog permission)")
@RestController
@RequestMapping("/api/v1/vehicle-models")
@PreAuthorize(CatalogAccess.READ)
@RequiredArgsConstructor
public class VehicleModelController {

    private final VehicleModelService vehicleModelService;

    @Operation(summary = "Vehicle models by manufacturer (UC-22)")
    @GetMapping
    public List<VehicleModelResponse> list(@RequestParam(required = false) String q,
                                           @RequestParam(required = false) VehicleType vehicleType,
                                           @RequestParam(required = false) VehicleModelStatus status) {
        return vehicleModelService.list(q, vehicleType, status);
    }

    @Operation(summary = "One vehicle model")
    @GetMapping("/{id}")
    public VehicleModelResponse detail(@PathVariable long id) {
        return vehicleModelService.get(id);
    }

    @Operation(summary = "Create a vehicle model (UC-23)")
    @PostMapping
    @PreAuthorize(CatalogAccess.EDIT)
    public ResponseEntity<VehicleModelResponse> create(@AuthenticationPrincipal AuthenticatedUser actor,
                                                       @Valid @RequestBody VehicleModelRequest request) {
        VehicleModelResponse created = vehicleModelService.create(actor, request);
        return ResponseEntity.created(URI.create("/api/v1/vehicle-models/" + created.id())).body(created);
    }

    @Operation(summary = "Update a vehicle model (UC-24)")
    @PutMapping("/{id}")
    @PreAuthorize(CatalogAccess.EDIT)
    public VehicleModelResponse update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long id,
                                       @Valid @RequestBody VehicleModelRequest request) {
        return vehicleModelService.update(actor, id, request);
    }

    @Operation(summary = "Stop using a vehicle model for new compatibility links (UC-25)")
    @PostMapping("/{id}/deactivate")
    @PreAuthorize(CatalogAccess.EDIT)
    public ResponseEntity<Void> deactivate(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long id) {
        vehicleModelService.changeStatus(actor, id, VehicleModelStatus.INACTIVE);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Use a deactivated vehicle model again")
    @PostMapping("/{id}/activate")
    @PreAuthorize(CatalogAccess.EDIT)
    public ResponseEntity<Void> activate(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long id) {
        vehicleModelService.changeStatus(actor, id, VehicleModelStatus.ACTIVE);
        return ResponseEntity.noContent().build();
    }
}
