package fu.hl.g64.motorflow.catalog.repository;

import fu.hl.g64.motorflow.catalog.entity.ProductIdentifier;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductIdentifierRepository extends JpaRepository<ProductIdentifier, Long> {
}
