package fu.hl.g64.motorflow.common;

import fu.hl.g64.motorflow.common.error.ApiError;
import fu.hl.g64.motorflow.common.error.GlobalExceptionHandler;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Conflicts between two users are reported as 409 with a message to retry, not as a server error. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(messages());
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/transfer-orders/1/dispatch");

    @Test
    void aGuardInOurOwnCodeMeansTheDocumentChangedSoTheUserReloads() {
        IllegalStateException guard = new IllegalStateException("transfer order 1 is RECEIVING");
        guard.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("fu.hl.g64.motorflow.transfer.entity.TransferOrder", "dispatch", "TransferOrder.java", 82)});

        ResponseEntity<ApiError> response = handler.handleIllegalState(guard, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("MSG29");
        assertThat(response.getBody().message()).contains("Vui lòng tải lại");
    }

    @Test
    void aGuardThrownFromALambdaInOurCodeIsAlsoAConflict() {
        Optional<Long> none = Optional.empty();
        IllegalStateException guard = null;
        try {
            none.orElseThrow(() -> new IllegalStateException("no PICKING result to confirm for 1"));
        } catch (IllegalStateException e) {
            guard = e;
        }

        assertThat(handler.handleIllegalState(guard, request).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void anIllegalStateFromALibraryIsStillAServerError() {
        IllegalStateException fromLibrary = new IllegalStateException("Iterator already consumed");
        fromLibrary.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("java.util.stream.AbstractPipeline", "evaluate", "AbstractPipeline.java", 230)});

        ResponseEntity<ApiError> response = handler.handleIllegalState(fromLibrary, request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().code()).isEqualTo("MSG07");
    }

    @Test
    void aLockTimeoutOrDeadlockAsksTheUserToTryAgain() {
        for (RuntimeException lockFailure : new RuntimeException[] {
                new CannotAcquireLockException("deadlock detected"),
                new PessimisticLockException("could not obtain lock"),
                new LockTimeoutException("lock timeout")}) {
            ResponseEntity<ApiError> response = handler.handleConcurrency(lockFailure, request);

            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(response.getBody().code()).isEqualTo("MSG248");
            assertThat(response.getBody().message()).isEqualTo(
                    "Dữ liệu này đang được người khác cập nhật nên chưa lưu được. Vui lòng thử lại.");
            assertThat(response.getBody().path()).isEqualTo("/api/v1/transfer-orders/1/dispatch");
        }
    }

    private static ResourceBundleMessageSource messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
