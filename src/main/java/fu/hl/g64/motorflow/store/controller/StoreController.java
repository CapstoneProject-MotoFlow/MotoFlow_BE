package fu.hl.g64.motorflow.store.controller;

import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.dto.CreateStoreRequest;
import fu.hl.g64.motorflow.store.dto.StoreResponse;
import fu.hl.g64.motorflow.store.dto.UpdateStoreRequest;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.service.StoreService;
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

@Tag(name = "Stores", description = "UC-03 View Store List, UC-04 Create Store, UC-06 Update Store (Admin, Owner)")
@RestController
@RequestMapping("/api/stores")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    @Operation(summary = "List stores with their warehouse, Branch Managers and stock count (UC-03)")
    @GetMapping
    public List<StoreResponse> list(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) StoreStatus status,
                                    @RequestParam(required = false) Boolean central) {
        return storeService.listStores(q, status, central);
    }

    @Operation(summary = "One store with its warehouse, Branch Managers and stock count")
    @GetMapping("/{id}")
    public StoreResponse detail(@PathVariable long id) {
        return storeService.getStore(id);
    }

    @Operation(summary = "Create a planned store and its warehouse (UC-04)")
    @PostMapping
    public ResponseEntity<StoreResponse> create(@AuthenticationPrincipal AuthenticatedUser actor,
                                                @Valid @RequestBody CreateStoreRequest request) {
        StoreResponse created = storeService.createStore(actor, request);
        return ResponseEntity.created(URI.create("/api/stores/" + created.id())).body(created);
    }

    @Operation(summary = "Update a store and its warehouse (UC-06)")
    @PutMapping("/{id}")
    public StoreResponse update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long id,
                                @Valid @RequestBody UpdateStoreRequest request) {
        return storeService.updateStore(actor, id, request);
    }
}
