package fu.hl.g64.motorflow.partner.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.partner.dto.SupplierDetailResponse;
import fu.hl.g64.motorflow.partner.dto.SupplierRequest;
import fu.hl.g64.motorflow.partner.dto.SupplierResponse;
import fu.hl.g64.motorflow.partner.entity.enums.SupplierStatus;
import fu.hl.g64.motorflow.partner.service.SupplierService;
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

@Tag(name = "Suppliers", description = "UC-30 to UC-33 (Owner only)")
@RestController
@RequestMapping("/api/v1/suppliers")
@PreAuthorize("hasRole('OWNER')")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    @Operation(summary = "Find suppliers by code, name or phone number (UC-30)")
    @GetMapping
    public PageResponse<SupplierResponse> list(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) SupplierStatus status,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return supplierService.list(q, status, page, size);
    }

    @Operation(summary = "Supplier profile with its purchase orders, deliveries and discrepancies (UC-32)")
    @GetMapping("/{id}")
    public SupplierDetailResponse detail(@PathVariable long id) {
        return supplierService.detail(id);
    }

    @Operation(summary = "Create a supplier; its code is unique and never changes (UC-31)")
    @PostMapping
    public ResponseEntity<SupplierResponse> create(@AuthenticationPrincipal AuthenticatedUser actor,
                                                   @Valid @RequestBody SupplierRequest request) {
        SupplierResponse created = supplierService.create(actor, request);
        return ResponseEntity.created(URI.create("/api/v1/suppliers/" + created.id())).body(created);
    }

    @Operation(summary = "Update a supplier's business or contact details (UC-33)")
    @PutMapping("/{id}")
    public SupplierResponse update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long id,
                                   @Valid @RequestBody SupplierRequest request) {
        return supplierService.update(actor, id, request);
    }

    @Operation(summary = "Stop offering a supplier for new purchase orders; its history stays")
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long id) {
        supplierService.changeStatus(actor, id, SupplierStatus.INACTIVE);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Offer a deactivated supplier again")
    @PostMapping("/{id}/activate")
    public ResponseEntity<Void> activate(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long id) {
        supplierService.changeStatus(actor, id, SupplierStatus.ACTIVE);
        return ResponseEntity.noContent().build();
    }
}
