package fu.hl.g64.motorflow.sales.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.sales.dto.UnmetDemandRow;
import fu.hl.g64.motorflow.sales.dto.UnmetDemandSearch;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/**
 * UC-59 View Unmet Demand (GVHD 24/11): the waiting orders, those that still wait (WAITING), can be released now
 * (READY), were released (RELEASED) or were cancelled (CANCELLED). A completed order that delivered less, an ordinary
 * cancelled order and the part a customer declined to wait for are not listed. Computed each time.
 */
public interface UnmetDemandService {

    /**
     * Ready orders first, then waiting ones, oldest first (the order stock is shared out in); then released and
     * cancelled ones, newest first. Owner sees every store or the one chosen; a Branch Manager their own store (MSG05
     * for another).
     */
    PageResponse<UnmetDemandRow> list(AuthenticatedUser user, UnmetDemandSearch search, int page, int size);
}
