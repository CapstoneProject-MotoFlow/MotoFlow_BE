package fu.hl.g64.motorflow.catalog.repository;

import fu.hl.g64.motorflow.catalog.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    boolean existsBySku(String sku);

    /** GVHD 20/11 (PR 5): the damaged-goods ("hàng lỗi") variant of a part, if it has one. */
    Optional<Product> findByVariantOfProductId(Long productId);

    /** GVHD 20/11 (PR 5): the damaged-goods ("hàng lỗi") variants of these parts. */
    List<Product> findByVariantOfProductIdIn(Collection<Long> productIds);

    boolean existsByCategoryId(Long categoryId);

    /** Locks the part while its moving average is recalculated, so two receipts cannot overwrite each other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(Long id);
}
