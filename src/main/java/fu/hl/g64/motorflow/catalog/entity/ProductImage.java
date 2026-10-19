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

import java.time.LocalDateTime;

/** Images of a part shown in search and detail screens (UC-14, UC-16). */
@Entity
@Table(name = "product_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    /** {@code storageKey} is the image URL; the browser uploads the file to the image host itself. */
    public static ProductImage of(Long productId, String storageKey, int sortOrder, Long uploadedBy, LocalDateTime now) {
        ProductImage image = new ProductImage();
        image.productId = productId;
        image.storageKey = storageKey;
        image.sortOrder = (short) sortOrder;
        image.isPrimary = sortOrder == 0;
        image.uploadedBy = uploadedBy;
        image.uploadedAt = now;
        return image;
    }
}
