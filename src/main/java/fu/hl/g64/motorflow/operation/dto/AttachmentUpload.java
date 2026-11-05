package fu.hl.g64.motorflow.operation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * A file the browser has already uploaded to Cloudinary with an ATTACHMENT signature
 * ({@code POST /api/v1/uploads/signature}): {@code storageKey} is the {@code public_id} Cloudinary returned,
 * {@code fileName} the file's original name and {@code fileSize} its size in bytes.
 */
public record AttachmentUpload(
        @NotBlank(message = "Vui lòng nhập mã tệp đã tải lên.")
        @Size(max = 500, message = "Mã tệp tối đa 500 ký tự.")
        String storageKey,

        @NotBlank(message = "Vui lòng nhập tên tệp.")
        @Size(max = 255, message = "Tên tệp tối đa 255 ký tự.")
        String fileName,

        @NotBlank(message = "Vui lòng nhập loại tệp.")
        @Size(max = 100, message = "Loại tệp tối đa 100 ký tự.")
        String contentType,

        @NotNull(message = "Vui lòng nhập dung lượng tệp.")
        @Positive(message = "Dung lượng tệp phải lớn hơn 0.")
        Long fileSize) {
}
