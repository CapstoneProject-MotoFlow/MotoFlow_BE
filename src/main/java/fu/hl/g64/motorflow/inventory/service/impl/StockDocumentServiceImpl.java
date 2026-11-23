package fu.hl.g64.motorflow.inventory.service.impl;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.dto.StockDocument;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef.SourceType;
import fu.hl.g64.motorflow.inventory.repository.StockDocumentQuery;
import fu.hl.g64.motorflow.inventory.service.StockDocumentService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockDocumentServiceImpl implements StockDocumentService {

    private final StockDocumentQuery documents;

    @Override
    public PageResponse<StockDocument.Summary> list(AuthenticatedUser user, Long storeId, StockDocument.Search search,
                                                    int page, int size) {
        if (search.from() != null && search.to() != null && search.from().isAfter(search.to())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG02");
        }
        Long scope = scope(user, storeId);
        int offsetPage = Math.max(0, page);
        int pageSize = Math.clamp(size, 1, 100);
        String q = search.q() == null ? "" : search.q().strip().toLowerCase(Locale.ROOT);
        StockDocument.Search clean = new StockDocument.Search(search.direction(), search.type(), search.kind(),
                search.from(), search.to(), q);
        return new PageResponse<>(documents.page(scope, clean, isOwner(user), offsetPage, pageSize),
                documents.count(scope, clean), offsetPage, pageSize);
    }

    @Override
    public StockDocument.Detail detail(AuthenticatedUser user, SourceType type, long sourceId, Long storeId,
                                       StockDocument.Direction direction) {
        Long scope = scope(user, storeId);
        if (scope == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG02");
        }
        boolean owner = isOwner(user);
        StockDocument.Summary summary = documents.find(scope, type, sourceId, direction, owner)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG70"));
        return new StockDocument.Detail(summary, documents.lines(scope, type, sourceId, direction, owner));
    }

    private static boolean isOwner(AuthenticatedUser user) {
        return user.role() == Role.OWNER;
    }

    /** The Owner's choice (null is every warehouse); anyone else is held to their own warehouse. */
    private static Long scope(AuthenticatedUser user, Long requested) {
        if (isOwner(user)) {
            return requested;
        }
        if (user.storeId() == null || (requested != null && !requested.equals(user.storeId()))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        return user.storeId();
    }
}
