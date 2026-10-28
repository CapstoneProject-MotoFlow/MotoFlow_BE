package fu.hl.g64.motorflow.receiving.repository;

import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptRequestLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface GoodsReceiptRequestLineRepository extends JpaRepository<GoodsReceiptRequestLine, Long> {

    /** [goodsReceiptRequestId, number of lines, agreed value in VND] of each request. */
    @Query("""
            select l.goodsReceiptRequestId, count(l), sum(l.orderedQty * l.purchasePrice) from GoodsReceiptRequestLine l
            where l.goodsReceiptRequestId in :requestIds
            group by l.goodsReceiptRequestId""")
    List<Object[]> summarizeByRequest(Collection<Long> requestIds);
}
