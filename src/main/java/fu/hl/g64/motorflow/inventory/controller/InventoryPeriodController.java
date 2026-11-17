package fu.hl.g64.motorflow.inventory.controller;

import fu.hl.g64.motorflow.inventory.dto.InventoryPeriodResponse;
import fu.hl.g64.motorflow.inventory.dto.PeriodBalanceResponse;
import fu.hl.g64.motorflow.inventory.service.InventoryPeriodService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Inventory periods", description = "UC-99 View Inventory Period List (Owner)")
@RestController
@RequestMapping("/api/v1/inventory-periods")
@PreAuthorize("hasRole('OWNER')")
@RequiredArgsConstructor
public class InventoryPeriodController {

    private final InventoryPeriodService periodService;

    @Operation(summary = "Monthly periods of the whole chain, newest first, with status, who closed them and when; "
            + "nextToClose marks the oldest open period (UC-99, BR-82)")
    @GetMapping
    public List<InventoryPeriodResponse> list() {
        return periodService.list();
    }

    @Operation(summary = "Opening, in, out and closing quantity and value by warehouse and part, with the period "
            + "average unit cost (UC-99, BR-85). A closed period shows its stored balances; an open one is worked out "
            + "now and marked provisional. Optional storeId, q (SKU or part name) and categoryId (with subcategories)")
    @GetMapping("/{id}/balances")
    public PeriodBalanceResponse balances(@PathVariable long id,
                                          @RequestParam(required = false) Long storeId,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) Long categoryId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return periodService.balances(id, storeId, q, categoryId, page, size);
    }
}
