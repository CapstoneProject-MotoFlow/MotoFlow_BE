package fu.hl.g64.motorflow.upload.controller;

import fu.hl.g64.motorflow.account.entity.enums.PermissionCode;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.upload.dto.UploadPurpose;
import fu.hl.g64.motorflow.upload.dto.UploadSignatureRequest;
import fu.hl.g64.motorflow.upload.dto.UploadSignatureResponse;
import fu.hl.g64.motorflow.upload.service.CloudinaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Uploads", description = "File Upload Service: signed browser uploads to Cloudinary")
@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER', 'STAFF')")
public class UploadController {

    private final CloudinaryService cloudinary;

    @Operation(summary = "Sign one browser upload; product images need catalog rights (UC-15, UC-16)")
    @PostMapping("/signature")
    public UploadSignatureResponse signature(@AuthenticationPrincipal AuthenticatedUser user,
                                             @Valid @RequestBody UploadSignatureRequest request) {
        if (request.purpose() == UploadPurpose.PRODUCT_IMAGE && user.role() != Role.OWNER
                && !user.permissions().contains(PermissionCode.CATALOG_EDIT)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        return cloudinary.uploadSignature(request.purpose());
    }
}
