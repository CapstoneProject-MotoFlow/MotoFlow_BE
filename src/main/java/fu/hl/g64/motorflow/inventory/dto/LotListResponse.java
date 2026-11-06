package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.common.dto.PageResponse;

/** UC-97: one page of lots and the near-expiry window N, null if the Owner has not set one. */
public record LotListResponse(Integer expiryWarningDays, PageResponse<LotRowResponse> page) {
}
