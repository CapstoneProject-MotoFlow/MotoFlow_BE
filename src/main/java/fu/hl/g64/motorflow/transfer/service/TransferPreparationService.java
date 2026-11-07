package fu.hl.g64.motorflow.transfer.service;

import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.PreparedLinesRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferPreparationResponse;

/**
 * UC-57 Record Picked Quantities for an outgoing transfer: the person the source store's Branch Manager gave the
 * dispatch preparation to (UC-86, BR-32) records what they took out; UC-74 Confirm Transfer Dispatch then reads it.
 * Preparing changes no stock.
 */
public interface TransferPreparationService {

    /**
     * What to prepare. Owner, the Branch Manager of the source store and the person holding the task may look; MSG175
     * if there is no such transfer, MSG05 otherwise.
     */
    TransferPreparationResponse preparation(AuthenticatedUser user, long transferOrderId);

    /**
     * Records and sends the prepared quantity of every line; the preparer may correct it until the dispatch is
     * confirmed. MSG05 unless they hold the task, MSG29 once dispatched, MSG166 or MSG167 lines that do not match the
     * transfer, MSG176 more than planned.
     */
    TransferPreparationResponse recordPrepared(AuthenticatedUser preparer, long transferOrderId,
                                               PreparedLinesRequest request);
}
