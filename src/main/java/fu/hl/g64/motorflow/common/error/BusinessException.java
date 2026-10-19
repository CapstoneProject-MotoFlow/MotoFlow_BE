package fu.hl.g64.motorflow.common.error;

import org.springframework.http.HttpStatus;

/**
 * An expected rejection of a user action. {@code code} is a System Messages code from the SRS
 * (for example MSG09); its Vietnamese text lives in messages.properties and may take {@code args}.
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Object[] args;

    public BusinessException(HttpStatus status, String code, Object... args) {
        super(code);
        this.status = status;
        this.code = code;
        this.args = args;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Object[] getArgs() {
        return args;
    }
}
