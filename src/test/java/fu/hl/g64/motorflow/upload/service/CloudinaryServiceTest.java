package fu.hl.g64.motorflow.upload.service;

import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.upload.config.CloudinaryProperties;
import fu.hl.g64.motorflow.upload.dto.UploadPurpose;
import fu.hl.g64.motorflow.upload.dto.UploadSignatureResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudinaryServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1315060510), ZoneOffset.UTC);
    private final CloudinaryService service = new CloudinaryService(
            new CloudinaryProperties("demo", "1234", "abcd", Duration.ofMinutes(5)), CLOCK);

    @Test
    void signatureMatchesCloudinarysWorkedExamples() {
        // https://cloudinary.com/documentation/authentication_signatures
        assertThat(CloudinaryService.sign(Map.of("timestamp", 1315060510L), "abcd"))
                .isEqualTo("a21ad0f63beb4de2e5575204b79ab90bffb02c10");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("timestamp", 1315060510L);
        params.put("public_id", "sample_image");
        params.put("eager", "w_400,h_300,c_pad|w_260,h_200,c_crop");
        assertThat(CloudinaryService.sign(params, "abcd")).isEqualTo("bfd09f95f331f558cbd1320e67aa8d488770583e");
    }

    @Test
    void attachmentsUploadPrivatelyAndOnlyAsImagesOrPdf() {
        UploadSignatureResponse response = service.uploadSignature(UploadPurpose.ATTACHMENT);

        assertThat(response.uploadUrl()).isEqualTo("https://api.cloudinary.com/v1_1/demo/image/upload");
        assertThat(response.type()).isEqualTo("private");
        assertThat(response.folder()).isEqualTo("motoflow/attachments");
        assertThat(response.allowedFormats()).isEqualTo("jpg,jpeg,png,webp,pdf");
        assertThat(response.signature()).isEqualTo(CloudinaryService.sign(Map.of(
                "allowed_formats", "jpg,jpeg,png,webp,pdf", "folder", "motoflow/attachments",
                "timestamp", 1315060510L, "type", "private"), "abcd"));
    }

    @Test
    void privateDownloadLinkExpiresAfterTheConfiguredTime() {
        String url = service.privateDownloadUrl("motoflow/attachments/grn-01", "pdf");
        Map<String, String> query = UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap();

        assertThat(url).startsWith("https://api.cloudinary.com/v1_1/demo/image/download?");
        assertThat(query).containsEntry("public_id", "motoflow/attachments/grn-01")
                .containsEntry("type", "private")
                .containsEntry("expires_at", String.valueOf(1315060510L + 300))
                .containsEntry("api_key", "1234");
        assertThat(query.get("signature")).isEqualTo(CloudinaryService.sign(Map.of(
                "expires_at", 1315060810L, "format", "pdf", "public_id", "motoflow/attachments/grn-01",
                "timestamp", 1315060510L, "type", "private"), "abcd"));
    }

    @Test
    void withoutAnAccountUploadsAreOffAndImagesAreNotRestricted() {
        CloudinaryService off = new CloudinaryService(new CloudinaryProperties("", "", "", null), CLOCK);

        assertThatThrownBy(() -> off.uploadSignature(UploadPurpose.PRODUCT_IMAGE))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("MSG76"));
        assertThat(off.publicImagePrefix()).isNull();
        assertThat(service.publicImagePrefix()).isEqualTo("https://res.cloudinary.com/demo/image/upload/");
    }
}
