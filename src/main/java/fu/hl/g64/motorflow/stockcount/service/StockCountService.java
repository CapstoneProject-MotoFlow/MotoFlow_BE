package fu.hl.g64.motorflow.stockcount.service;

import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.stockcount.dto.CountRequestResponse;
import fu.hl.g64.motorflow.stockcount.dto.StockCountApproveRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountCancelRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountConfirmRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountCreateRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountDetailResponse;
import fu.hl.g64.motorflow.stockcount.dto.StockCountFilter;
import fu.hl.g64.motorflow.stockcount.dto.StockCountLinesSaveRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountListResponse;
import fu.hl.g64.motorflow.stockcount.dto.StockCountRecountRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountSubmitRequest;
import org.springframework.data.domain.Pageable;

/** UC-90 to UC-95 Stock counts (R4 II.1), year-end and cycle counts. Opening counts (UC-96) come later. */
public interface StockCountService {

    /** UC-91: the Owner's PENDING request to the Branch Manager's store, to fill in a new count. */
    CountRequestResponse getPrefill(AuthenticatedUser user, long requestId);

    /** UC-91: a DRAFT count of the Branch Manager's warehouse. */
    StockCountDetailResponse create(AuthenticatedUser user, StockCountCreateRequest request);

    /** UC-90 */
    StockCountListResponse getStockCounts(AuthenticatedUser user, StockCountFilter filter, Pageable pageable);

    /** UC-92 */
    StockCountDetailResponse getStockCount(AuthenticatedUser user, long id);

    /** UC-94: cancels a count that never went to the Owner. */
    StockCountDetailResponse cancel(AuthenticatedUser user, long id, StockCountCancelRequest request);

    /** UC-93: the counter starts; book quantities are taken (BR-79). */
    StockCountDetailResponse start(AuthenticatedUser user, long id);

    /** UC-93: saves counted quantities as a draft. */
    StockCountDetailResponse saveLines(AuthenticatedUser user, long id, StockCountLinesSaveRequest request);

    /** UC-93: sends the counted quantities to the Branch Manager. Stock does not change. */
    StockCountDetailResponse submit(AuthenticatedUser user, long id, StockCountSubmitRequest request);

    /** UC-94: the Branch Manager confirms the result; done if nothing differs, otherwise to the Owner. */
    StockCountDetailResponse confirm(AuthenticatedUser user, long id, StockCountConfirmRequest request);

    /** UC-94: the Branch Manager or the Owner sends lines back to the counter. */
    StockCountDetailResponse requestRecount(AuthenticatedUser user, long id, StockCountRecountRequest request);

    /** UC-95: the Owner approves; differences are posted by lot into the count's period (BR-81, BR-83). */
    StockCountDetailResponse approve(AuthenticatedUser user, long id, StockCountApproveRequest request);
}
