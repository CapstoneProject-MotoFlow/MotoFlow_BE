package fu.hl.g64.motorflow.catalog.controller;

import fu.hl.g64.motorflow.catalog.dto.ProductRequest;
import fu.hl.g64.motorflow.catalog.dto.ProductResponse;
import fu.hl.g64.motorflow.catalog.dto.ProductSearch;
import fu.hl.g64.motorflow.catalog.dto.ProductSummaryResponse;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.service.ProductService;
import fu.hl.g64.motorflow.common.dto.PageResponse;
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

@Tag(name = "Spare parts", description = "UC-13 to UC-17: search for Owner, Branch Manager and Staff; changes need catalog rights")
@RestController
@RequestMapping("/api/v1/products")
@PreAuthorize(CatalogAccess.READ)
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "Search parts with price and stock of the viewed store (UC-13)")
    @GetMapping
    public PageResponse<ProductSummaryResponse> search(@AuthenticationPrincipal AuthenticatedUser user,
                                                       ProductSearch filter,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return productService.search(user, filter, page, size);
    }

    @Operation(summary = "Part detail with compatibility, prices and stock of the viewed store (UC-14)")
    @GetMapping("/{id}")
    public ProductResponse detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                  @RequestParam(required = false) Long storeId) {
        return productService.detail(user, id, storeId);
    }

    @Operation(summary = "Create a part (UC-15)")
    @PostMapping
    @PreAuthorize(CatalogAccess.EDIT)
    public ResponseEntity<ProductResponse> create(@AuthenticationPrincipal AuthenticatedUser actor,
                                                  @Valid @RequestBody ProductRequest request) {
        ProductResponse created = productService.create(actor, request);
        return ResponseEntity.created(URI.create("/api/v1/products/" + created.id())).body(created);
    }

    @Operation(summary = "Update a part; the SKU never changes (UC-16)")
    @PutMapping("/{id}")
    @PreAuthorize(CatalogAccess.EDIT)
    public ProductResponse update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long id,
                                  @Valid @RequestBody ProductRequest request) {
        return productService.update(actor, id, request);
    }

    @Operation(summary = "Stop selling and buying a part (UC-17)")
    @PostMapping("/{id}/discontinue")
    @PreAuthorize(CatalogAccess.EDIT)
    public ResponseEntity<Void> discontinue(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long id) {
        productService.changeStatus(actor, id, ProductStatus.DISCONTINUED);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Sell and buy a discontinued part again")
    @PostMapping("/{id}/reactivate")
    @PreAuthorize(CatalogAccess.EDIT)
    public ResponseEntity<Void> reactivate(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long id) {
        productService.changeStatus(actor, id, ProductStatus.ACTIVE);
        return ResponseEntity.noContent().build();
    }
}
