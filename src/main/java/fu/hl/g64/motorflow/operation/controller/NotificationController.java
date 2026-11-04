package fu.hl.g64.motorflow.operation.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.operation.dto.NotificationResponse;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Notifications", description = "UC-87 (Owner, Branch Manager, Staff): only the signed-in user's own")
@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER', 'STAFF')")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "My notifications, newest first; unreadOnly=true hides those already read (UC-87)")
    @GetMapping
    public PageResponse<NotificationResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @RequestParam(defaultValue = "false") boolean unreadOnly,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return notificationService.list(user, unreadOnly, page, size);
    }

    @Operation(summary = "Mark one notification read, e.g. when opening its document (UC-87)")
    @PostMapping("/{id}/read")
    public NotificationResponse markRead(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return notificationService.markRead(user, id);
    }

    @Operation(summary = "Mark all my notifications read (UC-87)")
    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("marked", notificationService.markAllRead(user));
    }
}
