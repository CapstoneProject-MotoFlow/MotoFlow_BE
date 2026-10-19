package fu.hl.g64.motorflow.catalog.repository;

import fu.hl.g64.motorflow.catalog.entity.ProductIdentifier;
import fu.hl.g64.motorflow.catalog.entity.enums.IdentifierType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ProductIdentifierRepository extends JpaRepository<ProductIdentifier, Long> {

    List<ProductIdentifier> findByProductIdIn(Collection<Long> productIds);

    /** A barcode belongs to one part only (unique index in the schema); OEM codes may repeat. */
    List<ProductIdentifier> findByIdentifierTypeAndIdentifierValueIn(IdentifierType type, Collection<String> values);

    /** Runs at once, so new rows saved after it never clash with the unique keys of the old ones. */
    @Modifying
    @Query("delete from ProductIdentifier x where x.productId = :productId")
    void deleteByProductId(Long productId);
}
