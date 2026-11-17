package fu.hl.g64.motorflow.document.dto;

import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-80: what a printed form shows; the front end lays it out, prints it or saves it as PDF. Unit prices and amounts
 * of a goods receipt note are null except for Owner (BR-72); a stock issue note carries no prices.
 * {@code store}: the issuing store; {@code counterpart}: the customer, supplier or other store.
 */
public record DocumentPrint(PrintForm form, DocumentType documentType, long id, String number, String status,
                            LocalDateTime documentDate, LocalDateTime printedAt, String printedBy, Party store,
                            Party counterpart, List<Line> lines, Totals totals, Payment payment, String note) {

    /** A store, customer or supplier as printed. */
    public record Party(Long id, String name, String address, String phone, String taxCode) {
    }

    /** {@code quantity} is negative on an adjustment note line that takes stock out. */
    public record Line(int no, Long productId, String sku, String partName, String baseUnit, Long lotId,
                       String manufacturerLotNo, int quantity, Long unitPrice, Long amount) {
    }

    /**
     * Retail invoice: {@code referenceTotal} at reference prices, {@code discountAmount}, the order
     * {@code discountRate}, {@code total} to pay, {@code paid} (verified payments minus refunds) and {@code credit}
     * (put on the customer's debt). Other forms fill {@code total} only, when it has a meaning for the reader.
     */
    public record Totals(Long referenceTotal, Long discountAmount, BigDecimal discountRate, Long total, Long paid,
                         Long credit) {
    }

    /** The payment a receipt shows, and for a debt payment the receivables it paid. */
    public record Payment(PaymentType paymentType, PaymentMethod paymentMethod, long amount, Long tenderedAmount,
                          Long changeGiven, String transactionReference, List<PaymentContent.Allocation> allocations) {
    }
}
