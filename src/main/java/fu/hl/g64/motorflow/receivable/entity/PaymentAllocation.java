package fu.hl.g64.motorflow.receivable.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The part of a debt payment that settles one receivable; one payment can settle several (V10). */
@Entity
@Table(name = "payment_allocation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "receivable_id", nullable = false)
    private Long receivableId;

    @Column(name = "allocated_amount", nullable = false)
    private long allocatedAmount;
}
