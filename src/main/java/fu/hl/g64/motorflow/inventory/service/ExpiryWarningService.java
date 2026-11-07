package fu.hl.g64.motorflow.inventory.service;

import fu.hl.g64.motorflow.inventory.dto.ExpiryWarningRequest;
import fu.hl.g64.motorflow.inventory.dto.ExpiryWarningResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/** UC-104: the chain-wide number of days before expiry at which lots raise a warning (BR-76). */
public interface ExpiryWarningService {

    ExpiryWarningResponse expiryWarning();

    /** Saves a new version; one for a later day replaces the version still waiting for its date. */
    ExpiryWarningResponse setExpiryWarning(AuthenticatedUser owner, ExpiryWarningRequest request);
}
