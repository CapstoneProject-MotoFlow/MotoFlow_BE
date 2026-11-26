package fu.hl.g64.motorflow.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * OEM codes of a part; a part may have several and a code may repeat across parts. They never replace the SKU
 * (BR-06).
 */
@Entity
@Table(name = "product_oem_code")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductOemCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "oem_code", nullable = false, length = 100)
    private String oemCode;

    public static ProductOemCode of(Long productId, String oemCode) {
        ProductOemCode code = new ProductOemCode();
        code.productId = productId;
        code.oemCode = oemCode;
        return code;
    }
}
