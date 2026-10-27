package fu.hl.g64.motorflow.partner.entity;

import fu.hl.g64.motorflow.partner.entity.enums.CreditStatus;
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

/** A customer with a profile who may be granted credit eligibility by Owner. */
@Entity
@Table(name = "customer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_name", nullable = false, length = 150)
    private String customerName;

    @Column(length = 20)
    private String phone;

    @Column
    private String address;

    @Column(name = "tax_code", length = 20)
    private String taxCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "credit_status", nullable = false, length = 20)
    private CreditStatus creditStatus;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
