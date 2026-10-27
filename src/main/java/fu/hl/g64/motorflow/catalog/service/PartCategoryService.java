package fu.hl.g64.motorflow.catalog.service;

import fu.hl.g64.motorflow.catalog.dto.PartCategoryRequest;
import fu.hl.g64.motorflow.catalog.dto.PartCategoryResponse;
import fu.hl.g64.motorflow.catalog.entity.PartCategory;
import fu.hl.g64.motorflow.catalog.entity.enums.PartCategoryStatus;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleType;
import fu.hl.g64.motorflow.catalog.repository.PartCategoryRepository;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** UC-18 View, UC-19 Create, UC-20 Update and UC-21 Deactivate Part Category (BR-05). */
@Service
@RequiredArgsConstructor
public class PartCategoryService {

    private final PartCategoryRepository categories;
    private final ProductRepository products;
    private final AuditLogRepository auditLog;
    private final Clock clock;

    /** Top-level categories with their sub-categories, by name; inactive ones included so they can be reactivated. */
    @Transactional(readOnly = true)
    public List<PartCategoryResponse> tree(VehicleType vehicleType) {
        List<PartCategory> all = categories.findAll(Sort.by("categoryName"));
        Map<Long, List<PartCategory>> childrenByParent = byParent(all);
        return all.stream()
                .filter(c -> c.getParentId() == null && (vehicleType == null || c.getVehicleType() == vehicleType))
                .map(c -> node(c, childrenByParent))
                .toList();
    }

    @Transactional(readOnly = true)
    public PartCategoryResponse get(long id) {
        PartCategory category = find(id);
        return node(category, byParent(categories.findAll(Sort.by("categoryName"))));
    }

    @Transactional
    public PartCategoryResponse create(AuthenticatedUser actor, PartCategoryRequest request) {
        String name = request.categoryName().strip();
        if (request.parentId() != null) {
            requireUsableParent(null, request.parentId(), request.vehicleType());
        }
        requireUniqueName(null, request.vehicleType(), request.parentId(), name);

        LocalDateTime now = LocalDateTime.now(clock);
        PartCategory category = save(PartCategory.create(name, request.vehicleType(), request.parentId(),
                blankToNull(request.description()), now), name);
        auditLog.save(AuditLog.record("PART_CATEGORY", category.getId(), "PART_CATEGORY_CREATED", actor.id(), null, now));
        return PartCategoryResponse.from(category, List.of());
    }

    /** Moving to another vehicle type moves the whole branch, so a parent and its children always match. */
    @Transactional
    public PartCategoryResponse update(AuthenticatedUser actor, long id, PartCategoryRequest request) {
        PartCategory category = find(id);
        String name = request.categoryName().strip();
        VehicleType oldType = category.getVehicleType();
        boolean moved = !Objects.equals(request.parentId(), category.getParentId()) || request.vehicleType() != oldType;
        if (moved && request.parentId() != null) {
            requireUsableParent(id, request.parentId(), request.vehicleType());
        }
        requireUniqueName(id, request.vehicleType(), request.parentId(), name);

        LocalDateTime now = LocalDateTime.now(clock);
        category.update(name, request.vehicleType(), request.parentId(), blankToNull(request.description()), now);
        List<PartCategory> all = categories.findAll(Sort.by("categoryName"));
        Map<Long, List<PartCategory>> childrenByParent = byParent(all);
        if (request.vehicleType() != oldType) {
            descendants(id, childrenByParent).forEach(c -> c.changeVehicleType(request.vehicleType(), now));
        }
        save(category, name);
        auditLog.save(AuditLog.record("PART_CATEGORY", id, "PART_CATEGORY_UPDATED", actor.id(), null, now));
        return node(category, childrenByParent);
    }

    /** UC-21. Parts already in the category keep it; no new part or sub-category may use it. */
    @Transactional
    public void changeStatus(AuthenticatedUser actor, long id, PartCategoryStatus status) {
        PartCategory category = find(id);
        if (category.getStatus() == status) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        category.changeStatus(status, now);
        String action = status == PartCategoryStatus.ACTIVE ? "PART_CATEGORY_ACTIVATED" : "PART_CATEGORY_DEACTIVATED";
        auditLog.save(AuditLog.record("PART_CATEGORY", id, action, actor.id(), null, now));
    }

    /** Parts may only be put in an active category without sub-categories (SCR-08). */
    public PartCategory requireLeafForParts(Long categoryId) {
        return categories.findById(categoryId)
                .filter(c -> c.getStatus() == PartCategoryStatus.ACTIVE && !categories.existsByParentId(c.getId()))
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG55"));
    }

    /** The category itself and every category below it. */
    public List<Long> withDescendants(long categoryId) {
        List<Long> ids = new ArrayList<>(List.of(categoryId));
        descendants(categoryId, byParent(categories.findAll())).forEach(c -> ids.add(c.getId()));
        return ids;
    }

    private void requireUsableParent(Long selfId, long parentId, VehicleType vehicleType) {
        PartCategory parent = categories.findById(parentId)
                .filter(p -> p.getStatus() == PartCategoryStatus.ACTIVE && p.getVehicleType() == vehicleType)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG59"));
        // Walking up from the new parent must never reach the category being moved.
        for (PartCategory c = parent; c != null; c = c.getParentId() == null ? null : categories.findById(c.getParentId()).orElse(null)) {
            if (c.getId().equals(selfId)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG59");
            }
        }
        if (products.existsByCategoryId(parentId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG60", parent.getCategoryName());
        }
    }

    private void requireUniqueName(Long selfId, VehicleType vehicleType, Long parentId, String name) {
        categories.findFirstByVehicleTypeAndParentIdAndCategoryNameIgnoreCase(vehicleType, parentId, name)
                .filter(other -> !other.getId().equals(selfId))
                .ifPresent(other -> {
                    throw new BusinessException(HttpStatus.CONFLICT, "MSG58", name);
                });
    }

    private PartCategory save(PartCategory category, String name) {
        try {
            return categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            // Same name saved by someone else a moment earlier.
            throw new BusinessException(HttpStatus.CONFLICT, "MSG58", name);
        }
    }

    private PartCategory find(long id) {
        return categories.findById(id).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG61"));
    }

    private static Map<Long, List<PartCategory>> byParent(List<PartCategory> all) {
        return all.stream().filter(c -> c.getParentId() != null).collect(Collectors.groupingBy(PartCategory::getParentId));
    }

    private static List<PartCategory> descendants(long id, Map<Long, List<PartCategory>> childrenByParent) {
        List<PartCategory> found = new ArrayList<>();
        Deque<Long> queue = new ArrayDeque<>(List.of(id));
        while (!queue.isEmpty()) {
            for (PartCategory child : childrenByParent.getOrDefault(queue.poll(), List.of())) {
                found.add(child);
                queue.add(child.getId());
            }
        }
        return found;
    }

    private static PartCategoryResponse node(PartCategory category, Map<Long, List<PartCategory>> childrenByParent) {
        return PartCategoryResponse.from(category, childrenByParent.getOrDefault(category.getId(), List.of()).stream()
                .map(child -> node(child, childrenByParent)).toList());
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
