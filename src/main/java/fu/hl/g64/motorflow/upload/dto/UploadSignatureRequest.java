package fu.hl.g64.motorflow.upload.dto;

import jakarta.validation.constraints.NotNull;

public record UploadSignatureRequest(@NotNull(message = "Vui lòng chọn loại tệp.") UploadPurpose purpose) {
}
