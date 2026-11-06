package fu.hl.g64.motorflow.account.controller;

import fu.hl.g64.motorflow.account.dto.BranchManagerResponse;
import fu.hl.g64.motorflow.account.dto.ManagerPermissionsRequest;
import fu.hl.g64.motorflow.account.service.PermissionService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Branch Manager Permissions", description = "UC-37 (Owner)")
@RestController
@RequestMapping("/api/v1/branch-managers")
@PreAuthorize("hasRole('OWNER')")
@RequiredArgsConstructor
public class BranchManagerController {

    private final PermissionService permissionService;

    @Operation(summary = "Branch Managers of a store (or of the whole chain) with their catalog and retail price "
            + "permissions (UC-37)")
    @GetMapping
    public List<BranchManagerResponse> list(@RequestParam(required = false) Long storeId) {
        return permissionService.listBranchManagers(storeId);
    }

    @Operation(summary = "Grant or revoke a Branch Manager's catalog and retail price permissions (UC-37, BR-05, BR-08)")
    @PutMapping("/{id}/permissions")
    public BranchManagerResponse updatePermissions(@AuthenticationPrincipal AuthenticatedUser owner,
                                                   @PathVariable long id,
                                                   @Valid @RequestBody ManagerPermissionsRequest request) {
        return permissionService.updateManagerPermissions(owner, id, request);
    }
}
