package fu.hl.g64.motorflow.home.controller;

import fu.hl.g64.motorflow.home.dto.HomeResponse;
import fu.hl.g64.motorflow.home.service.HomeService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Home", description = "SCR-03 Home after sign-in (UC-01 step 7), every role")
@RestController
@RequestMapping("/api/home")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'BRANCH_MANAGER', 'STAFF')")
@RequiredArgsConstructor
public class HomeController {

    private final HomeService homeService;

    @Operation(summary = "Unread notifications and the documents waiting on the user, by role")
    @GetMapping
    public HomeResponse home(@AuthenticationPrincipal AuthenticatedUser user) {
        return homeService.home(user);
    }
}
