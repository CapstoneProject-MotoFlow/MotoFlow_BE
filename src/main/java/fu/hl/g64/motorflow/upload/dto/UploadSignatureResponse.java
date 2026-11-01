package fu.hl.g64.motorflow.upload.dto;

/**
 * What the browser posts to {@code uploadUrl} as multipart form fields, together with {@code file}.
 * The signature covers folder, type, allowed_formats and timestamp, and is valid for one hour.
 */
public record UploadSignatureResponse(String uploadUrl, String apiKey, long timestamp, String signature,
                                      String folder, String type, String allowedFormats) {
}
