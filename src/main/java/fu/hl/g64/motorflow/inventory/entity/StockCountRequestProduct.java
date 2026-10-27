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

/** A part to count, listed only when the request's scope is PRODUCTS. */
@Entity
@Table(name = "stock_count_request_product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockCountRequestProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_count_request_id", nullable = false)
    private Long stockCountRequestId;

    @Column(name = "product_id", nullable = false)
    private Long productId;
}
