package fu.hl.g64.motorflow.partner.entity;

import fu.hl.g64.motorflow.partner.entity.enums.EventType;
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

import java.time.LocalDateTime;

/** Each grant or revocation of a customer's credit eligibility by Owner (UC-63, UC-64). */
@Entity
@Table(name = "credit_eligibility_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditEligibilityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 10)
    private EventType eventType;

    @Column
    private String reason;

    @Column(name = "decided_by", nullable = false)
    private Long decidedBy;

    @Column(name = "decided_at", nullable = false)
    private LocalDateTime decidedAt;
}
