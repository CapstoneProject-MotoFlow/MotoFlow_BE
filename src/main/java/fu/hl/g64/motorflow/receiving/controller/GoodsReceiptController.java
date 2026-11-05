package fu.hl.g64.motorflow.receiving.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.receiving.dto.AssignedReceiptResponse;
import fu.hl.g64.motorflow.receiving.service.GoodsReceiptService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Goods receipt notes", description = "Supplier deliveries received at a warehouse (FE-05)")
@RestController
@RequestMapping("/api/v1/goods-receipt-notes")
@RequiredArgsConstructor
public class GoodsReceiptController {

    private final GoodsReceiptService goodsReceipts;

    @Operation(summary = "Supplier deliveries whose inspection the user holds and has not finished (UC-39, Staff)")
    @GetMapping("/assigned-to-me")
    @PreAuthorize("hasAnyRole('STAFF', 'BRANCH_MANAGER')")
    public PageResponse<AssignedReceiptResponse> assignedToMe(@AuthenticationPrincipal AuthenticatedUser user,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        return goodsReceipts.assignedToMe(user, page, size);
    }
}
