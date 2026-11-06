package fu.hl.g64.motorflow.sales.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.sales.dto.UnmetDemandRow;
import fu.hl.g64.motorflow.sales.dto.UnmetDemandSearch;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/**
 * UC-59 View Unmet Demand: what customers asked for and did not receive, computed from the sales order lines each time
 * (nothing is saved): the undelivered part of each completed order (BR-34, BR-35) and every line of a cancelled order.
 */
public interface UnmetDemandService {

    /** Newest first. Owner sees every store or the one chosen; a Branch Manager their own store (MSG05 for another). */
    PageResponse<UnmetDemandRow> list(AuthenticatedUser user, UnmetDemandSearch search, int page, int size);
}
