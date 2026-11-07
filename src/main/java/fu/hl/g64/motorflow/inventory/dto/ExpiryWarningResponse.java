package fu.hl.g64.motorflow.inventory.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-104: {@code inEffect} is the version that applies now and {@code waiting} the one waiting for its date (each null
 * if none); {@code history} holds the newest versions, the waiting one included, newest first.
 */
public record ExpiryWarningResponse(Version inEffect, Version waiting, List<Version> history) {

    public record Version(int days, LocalDateTime effectiveFrom, long setBy, String setByName) {
    }
}
