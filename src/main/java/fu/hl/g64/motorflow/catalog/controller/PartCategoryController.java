package fu.hl.g64.motorflow.catalog.controller;

import fu.hl.g64.motorflow.catalog.dto.PartCategoryRequest;
import fu.hl.g64.motorflow.catalog.dto.PartCategoryResponse;
import fu.hl.g64.motorflow.catalog.entity.enums.PartCategoryStatus;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleType;
import fu.hl.g64.motorflow.catalog.service.PartCategoryService;
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

@Tag(name = "Part categories", description = "UC-18 to UC-21 (Owner, Branch Manager with catalog permission)")
@RestController
@RequestMapping("/api/part-categories")
@PreAuthorize(CatalogAccess.READ)
@RequiredArgsConstructor
public class PartCategoryController {

    private final PartCategoryService categoryService;

    @Operation(summary = "Category tree, top-level categories first (UC-18)")
    @GetMapping
    public List<PartCategoryResponse> tree(@RequestParam(required = false) VehicleType vehicleType) {
        return categoryService.tree(vehicleType);
    }

    @Operation(summary = "Create a category, optionally under a parent of the same vehicle type (UC-19)")
    @PostMapping
    @PreAuthorize(CatalogAccess.EDIT)
    public ResponseEntity<PartCategoryResponse> create(@AuthenticationPrincipal AuthenticatedUser actor,
                                                       @Valid @RequestBody PartCategoryRequest request) {
        PartCategoryResponse created = categoryService.create(actor, request);
        return ResponseEntity.created(URI.create("/api/part-categories/" + created.id())).body(created);
    }

    @Operation(summary = "Rename or move a category (UC-20)")
    @PutMapping("/{id}")
    @PreAuthorize(CatalogAccess.EDIT)
    public PartCategoryResponse update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long id,
                                       @Valid @RequestBody PartCategoryRequest request) {
        return categoryService.update(actor, id, request);
    }

    @Operation(summary = "Stop using a category for new parts (UC-21)")
    @PostMapping("/{id}/deactivate")
    @PreAuthorize(CatalogAccess.EDIT)
    public ResponseEntity<Void> deactivate(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long id) {
        categoryService.changeStatus(actor, id, PartCategoryStatus.INACTIVE);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Use a deactivated category again")
    @PostMapping("/{id}/activate")
    @PreAuthorize(CatalogAccess.EDIT)
    public ResponseEntity<Void> activate(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long id) {
        categoryService.changeStatus(actor, id, PartCategoryStatus.ACTIVE);
        return ResponseEntity.noContent().build();
    }
}
