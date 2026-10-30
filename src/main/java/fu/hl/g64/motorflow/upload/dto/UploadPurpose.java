package fu.hl.g64.motorflow.upload.dto;

import java.util.List;

/**
 * What a file is for, which decides where it goes and who may see it.
 * Product images are public; documents and evidence are private and only reached through
 * a short-lived download link after the owning record's access check (NFR-S05).
 */
public enum UploadPurpose {
    PRODUCT_IMAGE("motoflow/products", "upload", List.of("jpg", "jpeg", "png", "webp")),
    ATTACHMENT("motoflow/attachments", "private", List.of("jpg", "jpeg", "png", "webp", "pdf"));

    private final String folder;
    private final String deliveryType;
    private final List<String> allowedFormats;

    UploadPurpose(String folder, String deliveryType, List<String> allowedFormats) {
        this.folder = folder;
        this.deliveryType = deliveryType;
        this.allowedFormats = allowedFormats;
    }

    public String folder() {
        return folder;
    }

    public String deliveryType() {
        return deliveryType;
    }

    public List<String> allowedFormats() {
        return allowedFormats;
    }
}
