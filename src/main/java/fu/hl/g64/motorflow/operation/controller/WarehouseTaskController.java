package fu.hl.g64.motorflow.operation.controller;

import fu.hl.g64.motorflow.operation.dto.AssignTaskRequest;
import fu.hl.g64.motorflow.operation.dto.ReceivingTaskResponse;
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

import java.util.List;

@Tag(name = "Warehouse tasks", description = "UC-86 (Branch Manager of the document's store): give picking, "
        + "inspection, dispatch preparation, counting or receiving (SUPPLIER_RECEIVING: documentId = purchase order; "
        + "TRANSFER_RECEIVING: documentId = transfer order) to a Staff member, or take it yourself")
@RestController
@RequestMapping("/api/v1/warehouse-tasks")
@PreAuthorize("hasRole('BRANCH_MANAGER')")
@RequiredArgsConstructor
public class WarehouseTaskController {

    private final WarehouseTaskService warehouseTaskService;

    @Operation(summary = "Current assignee, who the task can go to and the assignment history of one document "
            + "(UC-86 step 2)")
    @GetMapping
    public TaskAssignmentResponse assignment(@AuthenticationPrincipal AuthenticatedUser user,
                                             @RequestParam TaskType taskType, @RequestParam long documentId) {
        return warehouseTaskService.assignment(user, taskType, documentId);
    }

    @Operation(summary = "Receiving tasks the user holds and has not done: receive the goods of a purchase order "
            + "(SUPPLIER_RECEIVING) or of a transfer at its destination (TRANSFER_RECEIVING) and record the goods "
            + "receipt note", description = "taskType is required: SUPPLIER_RECEIVING or TRANSFER_RECEIVING (MSG02 "
            + "otherwise). Oldest assignment first.")
    @GetMapping("/assigned-to-me")
    @PreAuthorize("hasAnyRole('STAFF', 'BRANCH_MANAGER')")
    public List<ReceivingTaskResponse> receivingAssignedToMe(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @RequestParam TaskType taskType) {
        return warehouseTaskService.receivingAssignedToMe(user, taskType);
    }

    @Operation(summary = "Give the document's task to a Staff member or take it yourself (MSG40), or hand it to "
            + "someone else before the result is in (UC-86); sending the current assignee again changes nothing")
    @PostMapping
    public TaskAssignmentResponse assign(@AuthenticationPrincipal AuthenticatedUser user,
                                         @Valid @RequestBody AssignTaskRequest request) {
        return warehouseTaskService.assign(user, request);
    }
}
