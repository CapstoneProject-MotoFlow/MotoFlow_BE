package fu.hl.g64.motorflow.pricing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Time-effective versions of the wholesale price of a part at one store (BR-10). */
@Entity
@Table(name = "wholesale_price")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WholesalePrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "set_by", nullable = false)
    private Long setBy;

    @Column(name = "set_at", nullable = false)
    private LocalDateTime setAt;
}
