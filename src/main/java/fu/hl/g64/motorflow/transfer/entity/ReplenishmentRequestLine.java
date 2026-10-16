package fu.hl.g64.motorflow.transfer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A part and quantity requested on a replenishment request. */
@Entity
@Table(name = "replenishment_request_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReplenishmentRequestLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "replenishment_request_id", nullable = false)
    private Long replenishmentRequestId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "requested_qty", nullable = false)
    private int requestedQty;

    @Column(name = "to_purchase_qty", nullable = false)
    private int toPurchaseQty;
}
