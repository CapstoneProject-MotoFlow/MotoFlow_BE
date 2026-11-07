package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.Lot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface LotRepository extends JpaRepository<Lot, Long> {

    /** The purchase price of a part's lot from its most recent supplier receipt anywhere in the chain (BR-69). */
    interface LatestPurchase {
        Long getProductId();

        Long getPurchaseUnitPrice();

        LocalDate getReceivedDate();
    }

    /**
     * One row per part that was ever received from a supplier: the lot of the latest receipt date (the latest lot on
     * the same date). Opening and count-surplus lots are not purchases and are left out.
     */
    @Query(value = "select distinct on (l.product_id) l.product_id as productId, "
            + "l.purchase_unit_price as purchaseUnitPrice, l.received_date as receivedDate from lot l "
            + "where l.source_type = 'RECEIPT' and l.supplier_id is not null and l.product_id in (:productIds) "
            + "order by l.product_id, l.received_date desc, l.id desc", nativeQuery = true)
    List<LatestPurchase> findLatestPurchases(Collection<Long> productIds);

    /** Active parts most recently received from a supplier, newest first: sample parts for a price suggestion preview. */
    @Query(value = "select l.product_id from lot l join product p on p.id = l.product_id "
            + "where l.source_type = 'RECEIPT' and l.supplier_id is not null and p.status = 'ACTIVE' "
            + "group by l.product_id order by max(l.received_date) desc, max(l.id) desc limit :limit", nativeQuery = true)
    List<Long> findRecentlyPurchasedProductIds(int limit);

    /** The lots that confirmed GRN lines created; one lot per line at most (goods_receipt_note_line_id is unique). */
    List<Lot> findByGoodsReceiptNoteLineIdIn(Collection<Long> goodsReceiptNoteLineIds);
}
