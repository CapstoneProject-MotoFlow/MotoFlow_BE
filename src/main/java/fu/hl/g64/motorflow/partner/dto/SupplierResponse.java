package fu.hl.g64.motorflow.partner.dto;

import fu.hl.g64.motorflow.partner.entity.Supplier;
import fu.hl.g64.motorflow.partner.entity.enums.SupplierStatus;

import java.time.LocalDateTime;

/** A row of the supplier list (UC-30) and the profile part of the detail (UC-32). */
public record SupplierResponse(long id, String supplierCode, String supplierName, String contactName, String phone,
                               String email, String address, String taxCode, SupplierStatus status,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static SupplierResponse from(Supplier supplier) {
        return new SupplierResponse(supplier.getId(), supplier.getSupplierCode(), supplier.getSupplierName(),
                supplier.getContactName(), supplier.getPhone(), supplier.getEmail(), supplier.getAddress(),
                supplier.getTaxCode(), supplier.getStatus(), supplier.getCreatedAt(), supplier.getUpdatedAt());
    }
}
