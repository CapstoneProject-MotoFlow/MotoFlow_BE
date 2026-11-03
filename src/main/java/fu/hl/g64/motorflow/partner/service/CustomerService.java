package fu.hl.g64.motorflow.partner.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.partner.dto.CustomerDetailResponse;
import fu.hl.g64.motorflow.partner.dto.CustomerRequest;
import fu.hl.g64.motorflow.partner.dto.CustomerResponse;
import fu.hl.g64.motorflow.partner.entity.Customer;
import fu.hl.g64.motorflow.partner.entity.enums.CreditStatus;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.util.List;

/** UC-26 View Customer List, UC-27 Create Customer, UC-28 View Customer Detail, UC-29 Update Customer. */
public interface CustomerService {
    /** Rows of the order and payment lists on the detail screen; older ones are counted but not listed. */
    public static final int HISTORY_SIZE = 20;

    /**
     * By name. Every filter is optional; {@code q} matches name, phone number (spaces ignored) or customer number,
     * and {@code creditStatus = ELIGIBLE} lists the customers allowed to buy on credit.
     */
    public PageResponse<CustomerResponse> list(AuthenticatedUser user, String q, CreditStatus creditStatus, int page,
                                               int size);

    public CustomerDetailResponse detail(AuthenticatedUser user, long id);

    /** UC-27, also used while creating a sales order for a customer without a profile. */
    public CustomerResponse create(AuthenticatedUser actor, CustomerRequest request);

    /** UC-29. Orders keep the customer details recorded on them (customer snapshot). */
    public CustomerResponse update(AuthenticatedUser actor, long id, CustomerRequest request);
}
