package fu.hl.g64.motorflow.catalog.entity;

import fu.hl.g64.motorflow.catalog.entity.enums.IdentifierType;
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

/** Additional codes of a part: OEM codes and barcodes. They never replace the SKU (BR-06). */
@Entity
@Table(name = "product_identifier")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductIdentifier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "identifier_type", nullable = false, length = 10)
    private IdentifierType identifierType;

    @Column(name = "identifier_value", nullable = false, length = 100)
    private String identifierValue;
}
