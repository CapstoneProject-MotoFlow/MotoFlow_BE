package fu.hl.g64.motorflow.upload.service;

import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.upload.config.CloudinaryProperties;
import fu.hl.g64.motorflow.upload.dto.UploadPurpose;
import fu.hl.g64.motorflow.upload.dto.UploadSignatureResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Signs browser uploads to Cloudinary and builds download links for private files, so the API secret
 * never leaves the server and files never pass through it.
 */
@Service
@RequiredArgsConstructor
public class CloudinaryService {

    private static final String API = "https://api.cloudinary.com/v1_1/";

    private final CloudinaryProperties properties;
    private final Clock clock;

    public UploadSignatureResponse uploadSignature(UploadPurpose purpose) {
        requireConfigured();
        long timestamp = clock.instant().getEpochSecond();
        String allowedFormats = String.join(",", purpose.allowedFormats());
        Map<String, Object> params = Map.of("allowed_formats", allowedFormats, "folder", purpose.folder(),
                "timestamp", timestamp, "type", purpose.deliveryType());
        return new UploadSignatureResponse(API + properties.cloudName() + "/image/upload", properties.apiKey(),
                timestamp, sign(params, properties.apiSecret()), purpose.folder(), purpose.deliveryType(), allowedFormats);
    }

    /**
     * A link to the original of a private file that stops working after motoflow.cloudinary.download-url-ttl.
     * Call it only after checking that the user may see the record the file belongs to.
     */
    public String privateDownloadUrl(String publicId, String format) {
        requireConfigured();
        long now = clock.instant().getEpochSecond();
        Map<String, Object> params = Map.of("public_id", publicId, "format", format, "type", "private",
                "timestamp", now, "expires_at", now + properties.downloadUrlTtl().toSeconds());
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(API + properties.cloudName() + "/image/download");
        new TreeMap<>(params).forEach(url::queryParam);
        return url.queryParam("api_key", properties.apiKey())
                .queryParam("signature", sign(params, properties.apiSecret()))
                .encode().toUriString();
    }

    /** The start of every image this account delivers publicly, or null when uploads are off. */
    public String publicImagePrefix() {
        return properties.configured() ? "https://res.cloudinary.com/" + properties.cloudName() + "/image/upload/" : null;
    }

    /**
     * Cloudinary's request signature: parameters sorted by name, joined as name=value with '&',
     * the API secret appended, then SHA-1 in hex.
     */
    static String sign(Map<String, Object> params, String apiSecret) {
        String toSign = new TreeMap<>(params).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&")) + apiSecret;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(toSign.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "MSG76");
        }
    }
}
