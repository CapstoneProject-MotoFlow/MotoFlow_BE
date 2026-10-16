package fu.hl.g64.motorflow.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Error body returned by every endpoint. The frontend shows {@code message}; it may also switch
 * on {@code code} (an SRS System Messages code such as MSG09).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String code, String message, String path, Map<String, String> fieldErrors) {
}
