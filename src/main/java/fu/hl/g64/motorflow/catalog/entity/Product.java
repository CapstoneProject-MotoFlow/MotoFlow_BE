package fu.hl.g64.motorflow.catalog.entity;

import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
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

/** A spare part (SKU) stocked, priced and sold across the chain. */
@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String sku;

    @Column(name = "part_name", nullable = false, length = 200)
    private String partName;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(length = 100)
    private String brand;

    @Column(name = "country_of_origin", length = 100)
    private String countryOfOrigin;

    @Column(name = "base_unit", nullable = false, length = 20)
    private String baseUnit;

    @Column(columnDefinition = "text")
    private String specifications;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    @Column(name = "discontinued_at")
    private LocalDateTime discontinuedAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** UC-15. Creating a part never creates stock (DBR-02). */
    public static Product create(String sku, String partName, Long categoryId, String brand, String countryOfOrigin,
                                 String baseUnit, String specifications, Long createdBy, LocalDateTime now) {
        Product product = new Product();
        product.sku = sku;
        product.status = ProductStatus.ACTIVE;
        product.createdBy = createdBy;
        product.createdAt = now;
        product.update(partName, categoryId, brand, countryOfOrigin, baseUnit, specifications, now);
        return product;
    }

    /** UC-16. The SKU never changes, so stock and documents keep pointing at the same part. */
    public void update(String newPartName, Long newCategoryId, String newBrand, String newCountryOfOrigin,
                       String newBaseUnit, String newSpecifications, LocalDateTime now) {
        partName = newPartName;
        categoryId = newCategoryId;
        brand = newBrand;
        countryOfOrigin = newCountryOfOrigin;
        baseUnit = newBaseUnit;
        specifications = newSpecifications;
        updatedAt = now;
    }

    /** UC-17: no new sales orders or goods receipt requests; history stays (BR-07). */
    public void discontinue(LocalDateTime now) {
        status = ProductStatus.DISCONTINUED;
        discontinuedAt = now;
        updatedAt = now;
    }

    public void reactivate(LocalDateTime now) {
        status = ProductStatus.ACTIVE;
        discontinuedAt = null;
        updatedAt = now;
    }
}
