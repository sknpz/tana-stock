package io.github.sknpz.tana.movement;

import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.sknpz.tana.product.Product;
import io.github.sknpz.tana.product.ProductRepository;
import io.github.sknpz.tana.shared.DateRange;
import io.github.sknpz.tana.shared.exception.ResourceNotFoundException;
import io.github.sknpz.tana.user.CurrentUser;

@Service
public class StockMovementService {

    private final StockMovementRepository movementRepository;
    private final ProductRepository productRepository;
    private final CurrentUser currentUser;
    private final ZoneId zoneId;

    public StockMovementService(StockMovementRepository movementRepository, ProductRepository productRepository,
            CurrentUser currentUser, @Value("${app.zone-id}") ZoneId zoneId) {
        this.movementRepository = movementRepository;
        this.productRepository = productRepository;
        this.currentUser = currentUser;
        this.zoneId = zoneId;
    }

    @Transactional
    public StockMovementResponse register(Long productId, StockMovementRequest request) {
        Product product = productRepository.findActiveByIdForUpdate(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Produto", productId));

        String notes = request.notes() == null || request.notes().isBlank() ? null : request.notes().trim();
        StockMovement movement = product.registerMovement(request.type(), request.quantity(), notes,
                currentUser.reference().orElse(null));
        return StockMovementResponse.from(movementRepository.save(movement));
    }

    @Transactional(readOnly = true)
    public Page<StockMovementResponse> list(MovementType type, Long productId, DateRange period, Pageable pageable) {
        Specification<StockMovement> spec = StockMovementSpecifications.matching(type, productId,
                period.startInclusive(zoneId), period.endExclusive(zoneId));
        return movementRepository.findAll(spec, pageable).map(StockMovementResponse::from);
    }
}
