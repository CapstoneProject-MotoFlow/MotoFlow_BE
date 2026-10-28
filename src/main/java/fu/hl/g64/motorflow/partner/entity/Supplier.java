package fu.hl.g64.motorflow.partner.entity;

import fu.hl.g64.motorflow.partner.entity.enums.SupplierStatus;
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

/** A supplier whose deliveries the stores receive. Purchases are agreed outside MotoFlow. */
@Entity
@Table(name = "supplier")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "supplier_code", nullable = false, unique = true, length = 20)
    private String supplierCode;

    @Column(name = "supplier_name", nullable = false, length = 150)
    private String supplierName;

    @Column(name = "contact_name", length = 100)
    private String contactName;

    @Column(length = 20)
    private String phone;

    @Column(length = 100)
    private String email;

    @Column
    private String address;

    @Column(name = "tax_code", length = 20)
    private String taxCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupplierStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** UC-31. The code entered by Owner is the supplier's key and never changes afterwards. */
    public static Supplier create(String supplierCode, String supplierName, String contactName, String phone,
                                  String email, String address, String taxCode, LocalDateTime now) {
        Supplier supplier = new Supplier();
        supplier.supplierCode = supplierCode;
        supplier.status = SupplierStatus.ACTIVE;
        supplier.createdAt = now;
        supplier.update(supplierName, contactName, phone, email, address, taxCode, now);
        return supplier;
    }

    /** UC-33: business and contact details only; purchase orders already sent keep pointing at the same supplier. */
    public void update(String newSupplierName, String newContactName, String newPhone, String newEmail,
                       String newAddress, String newTaxCode, LocalDateTime now) {
        supplierName = newSupplierName;
        contactName = newContactName;
        phone = newPhone;
        email = newEmail;
        address = newAddress;
        taxCode = newTaxCode;
        updatedAt = now;
    }

    /** An inactive supplier keeps its history but is no longer offered for new purchase orders. */
    public void changeStatus(SupplierStatus newStatus, LocalDateTime now) {
        status = newStatus;
        updatedAt = now;
    }
}
