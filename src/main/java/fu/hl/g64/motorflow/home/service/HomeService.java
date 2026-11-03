package fu.hl.g64.motorflow.home.service;

import fu.hl.g64.motorflow.home.dto.HomeResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/** SCR-03 Home, opened after sign-in (UC-01 step 7): unread notifications and what waits on the user, by role. */
public interface HomeService {
    public HomeResponse home(AuthenticatedUser user);
}
