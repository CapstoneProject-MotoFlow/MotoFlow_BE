package fu.hl.g64.motorflow.receiving.entity;

import fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision;
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

/** Inspection result of one part in one delivery. */
@Entity
@Table(name = "goods_receipt_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GoodsReceiptLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "goods_receipt_id", nullable = false)
    private Long goodsReceiptId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "document_qty", nullable = false)
    private int documentQty;

    @Column(name = "delivered_qty", nullable = false)
    private int deliveredQty;

    @Column(name = "passed_qty", nullable = false)
    private int passedQty;

    @Column(name = "wrong_item_qty", nullable = false)
    private int wrongItemQty;

    @Column(name = "damaged_qty", nullable = false)
    private int damagedQty;

    @Column(name = "excess_qty", nullable = false)
    private int excessQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "excess_decision", nullable = false, length = 20)
    private ExcessDecision excessDecision;
}
