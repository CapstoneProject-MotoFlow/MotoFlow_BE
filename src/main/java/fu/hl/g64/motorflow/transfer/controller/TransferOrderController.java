package fu.hl.g64.motorflow.transfer.controller;

import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderCreateRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderResponse;
import fu.hl.g64.motorflow.transfer.service.TransferOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Tag(name = "Transfer orders", description = "UC-71 to UC-77")
@RestController
@RequestMapping("/api/v1/transfer-orders")
@RequiredArgsConstructor
public class TransferOrderController {

    private final TransferOrderService service;

    @Operation(summary = "Create a transfer order (UC-71). Owner or the central warehouse Branch Manager, from a "
            + "replenishment request or directly. Reserves no stock and posts no movement")
    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
    public ResponseEntity<TransferOrderResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                        @Valid @RequestBody TransferOrderCreateRequest request) {
        TransferOrderResponse created = service.create(user, request);
        return ResponseEntity.created(URI.create("/api/v1/transfer-orders/" + created.id())).body(created);
    }
}
