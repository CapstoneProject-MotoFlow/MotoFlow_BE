package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;

import java.time.LocalDateTime;

/**
 * One row of the sales order list (UC-52). {@code customerName} is the profile name, the walk-in name recorded, or
 * null for an anonymous walk-in sale ("Khách lẻ"). {@code total} is over the delivered quantities once confirmed,
 * the requested ones before; Staff get no total. {@code nextAction} is null once nothing waits on the user's side.
 */
public record SalesOrderSummary(long id, int version, SalesOrderStatus status, long storeId, String storeName,
                                Long customerId, String customerName, String customerPhone, LocalDateTime createdAt,
                                Long total, Long pickerId, String pickerName, NextAction nextAction) {
}
