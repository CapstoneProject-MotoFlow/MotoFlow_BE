package fu.hl.g64.motorflow.inventory.service;

import fu.hl.g64.motorflow.inventory.dto.LotAdjustPosting;
import fu.hl.g64.motorflow.inventory.dto.LotQuantity;
import fu.hl.g64.motorflow.inventory.dto.LotReceiptPosting;
import fu.hl.g64.motorflow.inventory.dto.MovePosting;
import fu.hl.g64.motorflow.inventory.dto.NewLotPosting;
import fu.hl.g64.motorflow.inventory.dto.OutPosting;
import fu.hl.g64.motorflow.inventory.dto.SplitMovePosting;
import fu.hl.g64.motorflow.inventory.entity.Lot;

import java.util.List;

/**
 * The only way to change stock (README section 2). Each call joins the caller's transaction and, for every lot and
 * status it touches, writes one stock_movement row and updates lot_balance and stock_balance by the same quantity
 * (BR-29), so the three always agree. Movements go into the open inventory period of today's month; the month is
 * opened on first use, and a closed month is refused (MSG85).
 *
 * <p>Concurrency: the stock_balance row of the part and warehouse is locked first, then the product row when the
 * moving average changes, then the lot_balance rows. Every locked row is read again from the database after the lock,
 * so an entity the caller loaded earlier in the transaction never hides another transaction's committed posting.
 * Two postings of the same part therefore never overwrite each other or sell the same unit.
 * A caller that posts several parts in one transaction should post them in product id order to avoid deadlocks.
 *
 * <p>Posting the same source document twice for the same lot and status breaks the unique key of stock_movement,
 * so a retried request cannot double the stock.
 */
public interface InventoryPostingService {

    /**
     * Stock in as a new lot (GRN line confirmed, opening count approved, count surplus without a lot). Recalculates
     * the chain-wide moving average from the AVAILABLE quantity at the lot's purchase unit price (BR-74). A
     * COUNT_SURPLUS lot comes in at the average and leaves it unchanged. {@code periodId} and {@code occurredAt}
     * follow the rule of {@link #adjustLot}.
     *
     * @return the new lot
     */
    Lot receiveNewLot(NewLotPosting posting);

    /**
     * Stock out of one status. Without given lots it takes FEFO, then FIFO. A sale or transfer dispatch never takes
     * an expired lot (MSG86) and never goes below zero (MSG84). Valued at the moving average.
     *
     * @return the lots and quantities actually taken, in picking order
     */
    List<LotQuantity> takeOut(OutPosting posting);

    /**
     * Moves goods between statuses or warehouses lot by lot, at the moving average; the value leaves and enters
     * with the same unit cost, so the average does not change.
     *
     * @return the lots and quantities moved
     */
    List<LotQuantity> move(MovePosting posting);

    /**
     * {@link #move} of exactly the given lots into several statuses at once (a transfer receipt: passed into AVAILABLE,
     * damaged into DAMAGED). The statuses are filled in enum order from the lots in the given order.
     */
    void moveToStatuses(SplitMovePosting posting);

    /**
     * Plus or minus on one known lot; never below zero (MSG84). A COUNT_ADJUSTMENT may name {@code periodId} and
     * {@code occurredAt} to land in the count's own period and at the end of the count day (BR-83): that period must
     * be OPEN (MSG85). Naming them for any other movement type, or only one of them, is an
     * {@link IllegalArgumentException}; naming neither posts into the current month as always.
     */
    void adjustLot(LotAdjustPosting posting);

    /**
     * Stock in on a lot that a supplier receipt created, at the lot's purchase price: excess that Owner accepts or
     * rejects after the GRN was confirmed (UC-45). Like {@link #receiveNewLot}, the AVAILABLE quantity recalculates the
     * moving average (BR-74), and the other statuses keep the goods out of it.
     */
    void receiveIntoLot(LotReceiptPosting posting);
}
