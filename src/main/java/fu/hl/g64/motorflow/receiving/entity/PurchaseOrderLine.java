package fu.hl.g64.motorflow.receiving.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Agreed quantity and purchase price of one part. */
@Entity
@Table(name = "purchase_order_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purchase_order_id", nullable = false)
    private Long purchaseOrderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "ordered_qty", nullable = false)
    private int orderedQty;

    @Column(name = "purchase_price", nullable = false)
    private long purchasePrice;
}
