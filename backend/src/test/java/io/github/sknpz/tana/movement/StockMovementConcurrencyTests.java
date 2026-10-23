package io.github.sknpz.tana.movement;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import io.github.sknpz.tana.IntegrationTest;
import io.github.sknpz.tana.product.ProductCreateRequest;
import io.github.sknpz.tana.product.ProductResponse;
import io.github.sknpz.tana.product.ProductService;
import io.github.sknpz.tana.shared.DateRange;
import io.github.sknpz.tana.shared.exception.BusinessException;

/**
 * Garante que o lock pessimista em {@code ProductRepository.findActiveByIdForUpdate} funciona:
 * saídas simultâneas não podem ler o mesmo saldo e deixar o estoque negativo ou inconsistente.
 */
class StockMovementConcurrencyTests extends IntegrationTest {

    private static final int INITIAL_STOCK = 7;
    private static final int CONCURRENT_EXITS = 20;

    @Autowired
    private ProductService productService;

    @Autowired
    private StockMovementService movementService;

    @Test
    void simultaneousExitsNeverTakeStockBelowZero() throws Exception {
        ProductResponse product = productService.create(new ProductCreateRequest(
                "Produto do teste de concorrência", null, null, null, new BigDecimal("10.00"), 0, INITIAL_STOCK));

        // Todas as threads esperam no mesmo ponto e disparam a saída juntas
        CountDownLatch start = new CountDownLatch(1);
        StockMovementRequest exitOfOne = new StockMovementRequest(MovementType.EXIT, 1, null);

        int successes = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_EXITS)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_EXITS; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        movementService.register(product.id(), exitOfOne);
                        return true;
                    } catch (BusinessException insufficientStock) {
                        return false;
                    }
                }));
            }
            start.countDown();

            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
        }

        assertThat(successes).isEqualTo(INITIAL_STOCK);
        assertThat(productService.get(product.id()).quantity()).isZero();

        // Histórico: a entrada "Estoque inicial" + uma saída por unidade, cada uma partindo
        // do saldo deixado pela anterior (nenhuma leu o mesmo saldo que outra)
        List<StockMovementResponse> history = movementService.list(null, product.id(), new DateRange(null, null), Pageable.unpaged())
                .getContent();
        assertThat(history).hasSize(1 + INITIAL_STOCK);
        assertThat(history)
                .filteredOn(movement -> movement.type() == MovementType.EXIT)
                .extracting(StockMovementResponse::previousQuantity)
                .containsExactlyInAnyOrder(7, 6, 5, 4, 3, 2, 1);
    }
}
