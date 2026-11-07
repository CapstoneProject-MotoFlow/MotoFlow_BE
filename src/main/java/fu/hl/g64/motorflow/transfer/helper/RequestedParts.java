package fu.hl.g64.motorflow.transfer.helper;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** UC-68, UC-71: the parts of a request or a transfer order appear once each and are still sold. */
@Component
@RequiredArgsConstructor
public class RequestedParts {

    private final ProductRepository products;

    public Map<Long, Product> requireActive(List<Long> ids) {
        if (ids.stream().distinct().count() != ids.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG93");
        }
        Map<Long, Product> found = products.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (Long id : ids) {
            Product part = found.get(id);
            if (part == null) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "MSG57");
            }
            if (part.getStatus() != ProductStatus.ACTIVE) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG94", part.getSku());
            }
        }
        return found;
    }
}
