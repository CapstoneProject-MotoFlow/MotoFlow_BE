package fu.hl.g64.motorflow.stockcount.controller;

import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.stockcount.dto.OpeningCostsResponse;
import fu.hl.g64.motorflow.stockcount.dto.OpeningCostsSaveRequest;
import fu.hl.g64.motorflow.stockcount.dto.OpeningLinesSaveRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountDetailResponse;
import fu.hl.g64.motorflow.stockcount.service.OpeningBalanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Opening balance", description = "UC-96 Record Opening Balance: the lines of an opening count and the "
        + "initial unit costs approved with it")
@RestController
@RequestMapping("/api/v1/stock-counts/{id}")
@RequiredArgsConstructor
public class OpeningBalanceController {

    private final OpeningBalanceService service;

    @Operation(summary = "Replace every line of an opening count being counted: one per part and expiry date, typed "
            + "or read from Excel by the client (UC-93, BR-86, the counter). MSG298 duplicate part and expiry date, "
            + "MSG266 damaged above counted, MSG242 unknown part")
    @PutMapping("/opening-lines")
    @PreAuthorize("hasRole('STAFF')")
    public StockCountDetailResponse saveLines(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                              @Valid @RequestBody OpeningLinesSaveRequest request) {
        return service.saveLines(user, id, request);
    }

    @Operation(summary = "The opening lots approval will create, by part and expiry date, with the initial unit "
            + "costs entered so far and the parts still missing one (UC-96, Owner)")
    @GetMapping("/opening-costs")
    @PreAuthorize("hasRole('OWNER')")
    public OpeningCostsResponse costs(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return service.costs(user, id);
    }

    @Operation(summary = "Enter initial unit costs, one per part for every warehouse, typed or read from Excel by the "
            + "client, while the opening count awaits approval (UC-96, BR-74, Owner). MSG299 a cost already approved "
            + "cannot change; approval refuses with MSG300 while a part has none")
    @PutMapping("/opening-costs")
    @PreAuthorize("hasRole('OWNER')")
    public OpeningCostsResponse saveCosts(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                          @Valid @RequestBody OpeningCostsSaveRequest request) {
        return service.saveCosts(user, id, request);
    }
}
