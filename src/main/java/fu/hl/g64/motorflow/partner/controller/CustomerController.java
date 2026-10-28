package fu.hl.g64.motorflow.partner.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.partner.dto.CustomerDetailResponse;
import fu.hl.g64.motorflow.partner.dto.CustomerRequest;
import fu.hl.g64.motorflow.partner.dto.CustomerResponse;
import fu.hl.g64.motorflow.partner.entity.enums.CreditStatus;
import fu.hl.g64.motorflow.partner.service.CustomerService;
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

@Tag(name = "Customers", description = "UC-26 to UC-29 (Owner, Branch Manager)")
@RestController
@RequestMapping("/api/customers")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @Operation(summary = "Find customers by name, phone or number, with their debts; creditStatus=ELIGIBLE lists those "
            + "allowed to buy on credit (UC-26)")
    @GetMapping
    public PageResponse<CustomerResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(required = false) CreditStatus creditStatus,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return customerService.list(user, q, creditStatus, page, size);
    }

    @Operation(summary = "Customer profile with credit history, debts, orders, payments and unmet demand (UC-28)")
    @GetMapping("/{id}")
    public CustomerDetailResponse detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return customerService.detail(user, id);
    }

    @Operation(summary = "Create a customer, from the list or while taking an order (UC-27)")
    @PostMapping
    public ResponseEntity<CustomerResponse> create(@AuthenticationPrincipal AuthenticatedUser actor,
                                                   @Valid @RequestBody CustomerRequest request) {
        CustomerResponse created = customerService.create(actor, request);
        return ResponseEntity.created(URI.create("/api/customers/" + created.id())).body(created);
    }

    @Operation(summary = "Update a customer's contact details; orders keep the details they recorded (UC-29)")
    @PutMapping("/{id}")
    public CustomerResponse update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long id,
                                   @Valid @RequestBody CustomerRequest request) {
        return customerService.update(actor, id, request);
    }
}
