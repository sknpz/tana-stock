package io.github.sknpz.tana.movement;

import java.time.Instant;

import org.springframework.data.jpa.domain.Specification;

final class StockMovementSpecifications {

    private StockMovementSpecifications() {
    }

    /** Filtros combinados por AND; argumento nulo é ignorado. {@code end} é exclusivo. */
    static Specification<StockMovement> matching(MovementType type, Long productId, Instant start, Instant end) {
        Specification<StockMovement> spec = Specification.unrestricted();
        if (type != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), type));
        }
        if (productId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("product").get("id"), productId));
        }
        if (start != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), start));
        }
        if (end != null) {
            spec = spec.and((root, query, cb) -> cb.lessThan(root.<Instant>get("createdAt"), end));
        }
        return spec;
    }
}
