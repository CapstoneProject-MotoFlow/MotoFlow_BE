package fu.hl.g64.motorflow.operation.controller;

import fu.hl.g64.motorflow.operation.dto.AssignTaskRequest;
import fu.hl.g64.motorflow.operation.dto.TaskAssignmentResponse;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.operation.service.WarehouseTaskService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Warehouse tasks", description = "UC-86 (Branch Manager of the document's store): give picking, "
        + "inspection, dispatch preparation or counting to a Staff member")
@RestController
@RequestMapping("/api/v1/warehouse-tasks")
@PreAuthorize("hasRole('BRANCH_MANAGER')")
@RequiredArgsConstructor
public class WarehouseTaskController {

    private final WarehouseTaskService warehouseTaskService;

    @Operation(summary = "Current assignee, eligible Staff and assignment history of one document (UC-86 step 2)")
    @GetMapping
    public TaskAssignmentResponse assignment(@AuthenticationPrincipal AuthenticatedUser user,
                                             @RequestParam TaskType taskType, @RequestParam long documentId) {
        return warehouseTaskService.assignment(user, taskType, documentId);
    }

    @Operation(summary = "Give the document's task to a Staff member, or to another one before the result is in "
            + "(UC-86); sending the current assignee again changes nothing")
    @PostMapping
    public TaskAssignmentResponse assign(@AuthenticationPrincipal AuthenticatedUser user,
                                         @Valid @RequestBody AssignTaskRequest request) {
        return warehouseTaskService.assign(user, request);
    }
}
