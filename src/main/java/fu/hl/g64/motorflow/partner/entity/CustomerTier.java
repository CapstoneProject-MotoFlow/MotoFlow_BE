package fu.hl.g64.motorflow.partner.entity;

import fu.hl.g64.motorflow.partner.entity.enums.TierStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A customer tier and its additional discount rate. A tier never grants credit eligibility. */
@Entity
@Table(name = "customer_tier")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tier_name", nullable = false, unique = true, length = 50)
    private String tierName;

    @Column(name = "additional_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal additionalRate;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private TierStatus status;

    @Column(name = "set_by", nullable = false)
    private Long setBy;

    @Column(name = "set_at", nullable = false)
    private LocalDateTime setAt;
}
