package fu.hl.g64.motorflow.partner.dto;

import fu.hl.g64.motorflow.partner.entity.Customer;
import fu.hl.g64.motorflow.partner.entity.enums.CreditStatus;

import java.time.LocalDateTime;

/**
 * A row of the customer list (UC-26). Debts are in VND: {@code totalDebt} is owed to every store of the chain (BR-53),
 * {@code debtAtOwnStore} to the viewer's own store; it is null for a viewer without a store (Owner).
 */
public record CustomerResponse(long id, String customerName, String phone, String address, String taxCode,
                               CreditStatus creditStatus, long totalDebt, Long debtAtOwnStore,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static CustomerResponse from(Customer customer, long totalDebt, Long debtAtOwnStore) {
        return new CustomerResponse(customer.getId(), customer.getCustomerName(), customer.getPhone(),
                customer.getAddress(), customer.getTaxCode(), customer.getCreditStatus(), totalDebt, debtAtOwnStore,
                customer.getCreatedAt(), customer.getUpdatedAt());
    }
}
