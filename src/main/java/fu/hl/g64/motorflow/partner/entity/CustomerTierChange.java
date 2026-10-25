package fu.hl.g64.motorflow.partner.entity;

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

/** One change of a customer's tier by Owner; a null tier removes it. Rows are never updated or deleted. */
@Entity
@Table(name = "customer_tier_change")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerTierChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_tier_id")
    private Long customerTierId;

    @Column
    private String reason;

    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;
}
