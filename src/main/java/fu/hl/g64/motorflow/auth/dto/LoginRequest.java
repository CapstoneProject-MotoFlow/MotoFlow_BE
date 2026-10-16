package fu.hl.g64.motorflow.auth.dto;

/** Blank fields are rejected by the service with MSG08, the message UC-01 asks for. */
public record LoginRequest(String username, String password) {
}
