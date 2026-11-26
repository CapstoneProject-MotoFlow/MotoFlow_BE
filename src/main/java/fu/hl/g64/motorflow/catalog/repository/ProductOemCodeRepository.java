package fu.hl.g64.motorflow.catalog.repository;

import fu.hl.g64.motorflow.catalog.entity.ProductOemCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ProductOemCodeRepository extends JpaRepository<ProductOemCode, Long> {

    List<ProductOemCode> findByProductIdIn(Collection<Long> productIds);

    /** Runs at once, so new rows saved after it never clash with the unique keys of the old ones. */
    @Modifying
    @Query("delete from ProductOemCode x where x.productId = :productId")
    void deleteByProductId(Long productId);
}
