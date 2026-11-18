package fu.hl.g64.motorflow.stockcount.service;

import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.stockcount.dto.OpeningCostsResponse;
import fu.hl.g64.motorflow.stockcount.dto.OpeningCostsSaveRequest;
import fu.hl.g64.motorflow.stockcount.dto.OpeningLinesSaveRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountDetailResponse;

/** UC-93 and UC-96 for an opening count: its lines, and the initial unit costs entered before approval. */
public interface OpeningBalanceService {

    /** The counter replaces every line of an opening count being counted (BR-86). */
    StockCountDetailResponse saveLines(AuthenticatedUser user, long countId, OpeningLinesSaveRequest request);

    /** Owner: the opening lots approval will create, with the initial unit costs entered so far. */
    OpeningCostsResponse costs(AuthenticatedUser user, long countId);

    /** Owner: enters initial unit costs of parts in an opening count awaiting approval (BR-74). */
    OpeningCostsResponse saveCosts(AuthenticatedUser user, long countId, OpeningCostsSaveRequest request);
}
