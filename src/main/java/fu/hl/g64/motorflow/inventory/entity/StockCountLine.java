package fu.hl.g64.motorflow.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Book and counted quantity of one part (and lot) in a stock count; the difference is derived, not stored. */
@Entity
@Table(name = "stock_count_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockCountLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_count_id", nullable = false)
    private Long stockCountId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "lot_id")
    private Long lotId;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "book_qty", nullable = false)
    private int bookQty;

    @Column(name = "counted_qty")
    private Integer countedQty;

    @Column(name = "counted_by")
    private Long countedBy;

    @Column(name = "counted_at")
    private LocalDateTime countedAt;

    @Column
    private String reason;

    @Column(name = "recount_requested", nullable = false)
    private boolean recountRequested;

    @Column(name = "recount_reason")
    private String recountReason;

    @Column(name = "previous_book_qty")
    private Integer previousBookQty;

    @Column(name = "previous_counted_qty")
    private Integer previousCountedQty;

    /** UC-91: a line to count; the book quantity is taken when counting starts (BR-79). */
    public static StockCountLine create(Long stockCountId, Long productId, Long lotId, LocalDate expiryDate) {
        StockCountLine line = new StockCountLine();
        line.stockCountId = stockCountId;
        line.productId = productId;
        line.lotId = lotId;
        line.expiryDate = expiryDate;
        return line;
    }

    /** UC-93: the book quantity when counting starts. */
    public void takeBookQty(int bookQty) {
        this.bookQty = bookQty;
    }

    /**
     * UC-93, BR-79: counting again after a recount request keeps the earlier book and counted quantities, takes a new
     * book quantity and clears the count.
     */
    public void restartRecount(int newBookQty) {
        previousBookQty = bookQty;
        previousCountedQty = countedQty;
        bookQty = newBookQty;
        countedQty = null;
        countedBy = null;
        countedAt = null;
    }

    /** UC-93: the counted quantity, or null to leave the line uncounted. */
    public void record(Integer countedQty, Long countedBy, LocalDateTime now) {
        this.countedQty = countedQty;
        this.countedBy = countedQty == null ? null : countedBy;
        this.countedAt = countedQty == null ? null : now;
    }

    /** UC-94: why the counted quantity differs from the book. */
    public void explain(String reason) {
        this.reason = reason;
    }

    /** UC-94: the line goes back to the counter. */
    public void requestRecount(String reason) {
        recountRequested = true;
        recountReason = reason;
    }

    /** UC-94: the recount was confirmed. */
    public void clearRecount() {
        recountRequested = false;
    }

    public boolean isCounted() {
        return countedQty != null;
    }

    /** Counted minus book, or null while uncounted. */
    public Integer difference() {
        return countedQty == null ? null : countedQty - bookQty;
    }
}
