package fu.hl.g64.motorflow.transfer.controller;

import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.PreparedLinesRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferPreparationResponse;
import fu.hl.g64.motorflow.transfer.service.TransferPreparationService;
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

@Tag(name = "Transfer dispatch preparation", description = "UC-57 for an outgoing transfer: the person holding the "
        + "dispatch preparation task (UC-86) records what they took out; UC-74 confirms the dispatch.")
@RestController
@RequestMapping("/api/v1/transfer-orders/{id}")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER', 'STAFF')")
@RequiredArgsConstructor
public class TransferPreparationController {

    private final TransferPreparationService preparation;

    @Operation(summary = "What to take out, where it is and which lots first, with what was recorded so far")
    @GetMapping("/preparation")
    public TransferPreparationResponse preparation(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @PathVariable long id) {
        return preparation.preparation(user, id);
    }

    @Operation(summary = "Send the prepared quantity of every line, or correct it before the dispatch is confirmed")
    @PutMapping("/prepared-lines")
    @PreAuthorize("hasAnyRole('STAFF', 'BRANCH_MANAGER')")
    public TransferPreparationResponse recordPrepared(@AuthenticationPrincipal AuthenticatedUser preparer,
                                                      @PathVariable long id,
                                                      @Valid @RequestBody PreparedLinesRequest request) {
        return preparation.recordPrepared(preparer, id, request);
    }
}
