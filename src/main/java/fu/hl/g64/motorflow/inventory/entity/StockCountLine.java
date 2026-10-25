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

    @Column
    private String reason;

    @Column(name = "recount_requested", nullable = false)
    private boolean recountRequested;
}
