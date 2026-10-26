package io.github.sknpz.tana.movement;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.sknpz.tana.product.Product;
import io.github.sknpz.tana.product.ProductRepository;
import io.github.sknpz.tana.shared.DateRange;
import io.github.sknpz.tana.shared.csv.CsvWriter;
import io.github.sknpz.tana.shared.exception.ResourceNotFoundException;
import io.github.sknpz.tana.shared.persistence.EntityStreams;
import io.github.sknpz.tana.user.CurrentUser;
import jakarta.persistence.EntityManager;

@Service
public class StockMovementService {

    private final StockMovementRepository movementRepository;
    private final ProductRepository productRepository;
    private final CurrentUser currentUser;
    private final ZoneId zoneId;
    private final EntityManager entityManager;

    public StockMovementService(StockMovementRepository movementRepository, ProductRepository productRepository,
            CurrentUser currentUser, @Value("${app.zone-id}") ZoneId zoneId, EntityManager entityManager) {
        this.entityManager = entityManager;
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

    /** Escreve o CSV direto na saída, linha a linha, dentro da transação (o stream lê do banco aos poucos). */
    @Transactional(readOnly = true)
    public void exportCsv(MovementType type, Long productId, DateRange period, OutputStream out) {
        Specification<StockMovement> spec = StockMovementSpecifications.matching(type, productId,
                period.startInclusive(zoneId), period.endExclusive(zoneId));
        try (CsvWriter csv = CsvWriter.open(out);
                Stream<StockMovement> movements = EntityStreams.stream(entityManager, StockMovement.class, spec,
                        Sort.by(Sort.Direction.DESC, "createdAt", "id"), "product", "user")) {
            csv.row("Data/hora", "Tipo", "Produto", "SKU", "Quantidade", "Saldo anterior", "Saldo novo",
                    "Observações", "Usuário");
            movements.forEach(movement -> csv.row(
                    CsvWriter.dateTime(movement.getCreatedAt(), zoneId),
                    movement.getType() == MovementType.ENTRY ? "Entrada" : "Saída",
                    CsvWriter.text(movement.getProduct().getName()),
                    CsvWriter.text(movement.getProduct().getSku()),
                    String.valueOf(movement.getQuantity()),
                    String.valueOf(movement.getPreviousQuantity()),
                    String.valueOf(movement.getNewQuantity()),
                    CsvWriter.text(movement.getNotes()),
                    movement.getUser() == null ? "" : CsvWriter.text(movement.getUser().getName())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
