package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.inventory.entity.StockBalance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.List;

/** UC-47: pages the parts that have at least one stock row matching a filter, in SQL. */
@Component
public class StockByProductQuery {

    /** One page of part ids, and how many parts match in all. */
    public record IdPage(List<Long> productIds, long total) {
    }

    private final EntityManager em;

    public StockByProductQuery(EntityManager em) {
        this.em = em;
    }

    /** Part ids ordered by SKU, {@code size} from {@code page * size}; the total counts parts, not stock rows. */
    public IdPage find(Specification<StockBalance> spec, int page, int size) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Long> ids = cb.createQuery(Long.class);
        Root<StockBalance> b = ids.from(StockBalance.class);
        Root<Product> p = ids.from(Product.class);
        ids.select(b.<Long>get("productId"))
                .where(cb.equal(p.get("id"), b.get("productId")), spec.toPredicate(b, ids, cb))
                .groupBy(b.get("productId"), p.get("sku"))
                .orderBy(cb.asc(p.get("sku")), cb.asc(b.get("productId")));
        List<Long> pageIds = em.createQuery(ids).setFirstResult(page * size).setMaxResults(size).getResultList();

        CriteriaQuery<Long> count = cb.createQuery(Long.class);
        Root<StockBalance> counted = count.from(StockBalance.class);
        count.select(cb.countDistinct(counted.get("productId"))).where(spec.toPredicate(counted, count, cb));
        return new IdPage(pageIds, em.createQuery(count).getSingleResult());
    }
}
