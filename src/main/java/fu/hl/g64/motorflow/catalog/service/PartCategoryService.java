package fu.hl.g64.motorflow.catalog.service;

import fu.hl.g64.motorflow.catalog.dto.PartCategoryRequest;
import fu.hl.g64.motorflow.catalog.dto.PartCategoryResponse;
import fu.hl.g64.motorflow.catalog.entity.PartCategory;
import fu.hl.g64.motorflow.catalog.entity.enums.PartCategoryStatus;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleType;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.util.List;

/** UC-18 View, UC-19 Create, UC-20 Update and UC-21 Deactivate Part Category (BR-05). */
public interface PartCategoryService {
    /** Top-level categories with their sub-categories, by name; inactive ones included so they can be reactivated. */
    List<PartCategoryResponse> tree(VehicleType vehicleType);

    PartCategoryResponse get(long id);

    PartCategoryResponse create(AuthenticatedUser actor, PartCategoryRequest request);

    /** Moving to another vehicle type moves the whole branch, so a parent and its children always match. */
    PartCategoryResponse update(AuthenticatedUser actor, long id, PartCategoryRequest request);

    /** UC-21. Parts already in the category keep it; no new part or sub-category may use it. */
    void changeStatus(AuthenticatedUser actor, long id, PartCategoryStatus status);

    /** Parts may only be put in an active category without sub-categories (SCR-08). */
    PartCategory requireLeafForParts(Long categoryId);

    /** The category itself and every category below it. */
    List<Long> withDescendants(long categoryId);
}
