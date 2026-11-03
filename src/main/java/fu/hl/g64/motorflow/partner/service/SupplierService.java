package fu.hl.g64.motorflow.partner.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.partner.dto.SupplierDetailResponse;
import fu.hl.g64.motorflow.partner.dto.SupplierRequest;
import fu.hl.g64.motorflow.partner.dto.SupplierResponse;
import fu.hl.g64.motorflow.partner.entity.Supplier;
import fu.hl.g64.motorflow.partner.entity.enums.SupplierStatus;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.util.List;

/** UC-30 View Supplier List, UC-31 Create Supplier, UC-32 View Supplier Detail, UC-33 Update Supplier (Owner). */
public interface SupplierService {
    /** By code. Every filter is optional; {@code q} matches code, name or phone number (spaces in phones are ignored). */
    PageResponse<SupplierResponse> list(String q, SupplierStatus status, int page, int size);

    SupplierDetailResponse detail(long id);

    /** UC-31. A new supplier is active and gets the next generated code. */
    SupplierResponse create(AuthenticatedUser actor, SupplierRequest request);

    /** UC-33. The supplier code never changes after creation (Data Business Rules: unique supplier code). */
    SupplierResponse update(AuthenticatedUser actor, long id, SupplierRequest request);

    /** Part of UC-33. Purchase orders, deliveries and discrepancies of the supplier are untouched. */
    void changeStatus(AuthenticatedUser actor, long id, SupplierStatus status);
}
