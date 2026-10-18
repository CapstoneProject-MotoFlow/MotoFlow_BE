package fu.hl.g64.motorflow.catalog.dto;

import fu.hl.g64.motorflow.catalog.entity.PartCategory;
import fu.hl.g64.motorflow.catalog.entity.enums.PartCategoryStatus;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleType;

import java.util.List;

/** A node of the category tree (UC-18). A part can only be put in a category without children. */
public record PartCategoryResponse(long id, String categoryName, VehicleType vehicleType, Long parentId,
                                   String description, PartCategoryStatus status,
                                   List<PartCategoryResponse> children) {

    public static PartCategoryResponse from(PartCategory category, List<PartCategoryResponse> children) {
        return new PartCategoryResponse(category.getId(), category.getCategoryName(), category.getVehicleType(),
                category.getParentId(), category.getDescription(), category.getStatus(), children);
    }
}
