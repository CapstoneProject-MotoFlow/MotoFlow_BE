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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Time-effective versions of the maximum invoice-wide discount rate set by Owner (BR-11). */
@Entity
@Table(name = "discount_threshold")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiscountThreshold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "max_discount_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxDiscountRate;

    @Column(name = "effective_from", nullable = false, unique = true)
    private LocalDateTime effectiveFrom;

    @Column(name = "set_by", nullable = false)
    private Long setBy;

    @Column(name = "set_at", nullable = false)
    private LocalDateTime setAt;
}
