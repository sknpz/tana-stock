package io.github.sknpz.tana.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import io.github.sknpz.tana.product.Product;

/** Consultas de agregação em SQL nativo: o banco soma e agrupa, em vez de carregar tudo na memória. */
public interface ReportRepository extends Repository<Product, Long> {

    @Query(value = """
            SELECT count(*)                                                        AS "totalProducts",
                   coalesce(sum(quantity), 0)                                      AS "totalQuantity",
                   coalesce(sum(quantity * unit_price), 0)                         AS "totalValue",
                   count(*) FILTER (WHERE quantity > 0 AND quantity <= min_stock)  AS "lowStockProducts",
                   count(*) FILTER (WHERE quantity = 0)                            AS "outOfStockProducts"
            FROM product
            WHERE active
            """, nativeQuery = true)
    InventoryTotals inventoryTotals();

    @Query(value = """
            SELECT coalesce(c.name, 'Sem categoria')  AS "category",
                   sum(p.quantity)                    AS "quantity",
                   sum(p.quantity * p.unit_price)     AS "value"
            FROM product p
            LEFT JOIN category c ON c.id = p.category_id
            WHERE p.active
            GROUP BY c.name
            ORDER BY "value" DESC
            """, nativeQuery = true)
    List<CategoryTotals> valueByCategory();

    // Ordena por um valor calculado (quantidade x preço), o que a listagem paginada de produtos não consegue fazer
    @Query(value = """
            SELECT p.id                       AS "id",
                   p.name                     AS "name",
                   c.name                     AS "category",
                   p.quantity                 AS "quantity",
                   p.quantity * p.unit_price  AS "totalValue"
            FROM product p
            LEFT JOIN category c ON c.id = p.category_id
            WHERE p.active
            ORDER BY "totalValue" DESC, p.name
            LIMIT :limit
            """, nativeQuery = true)
    List<ProductValue> mostValuableProducts(@Param("limit") int limit);

    @Query(value = "SELECT count(*) FROM category", nativeQuery = true)
    long countCategories();

    @Query(value = "SELECT count(*) FROM stock_movement WHERE created_at >= :since", nativeQuery = true)
    long countMovementsSince(@Param("since") Instant since);

    @Query(value = """
            SELECT count(*) FILTER (WHERE type = 'ENTRY')                           AS "entryCount",
                   coalesce(sum(quantity) FILTER (WHERE type = 'ENTRY'), 0)         AS "entryUnits",
                   count(*) FILTER (WHERE type = 'EXIT')                            AS "exitCount",
                   coalesce(sum(quantity) FILTER (WHERE type = 'EXIT'), 0)          AS "exitUnits"
            FROM stock_movement
            WHERE created_at >= :start AND created_at < :end
            """, nativeQuery = true)
    MovementTotalsRow movementTotals(@Param("start") Instant start, @Param("end") Instant end);

    interface MovementTotalsRow {
        Long getEntryCount();

        Long getEntryUnits();

        Long getExitCount();

        Long getExitUnits();
    }

    interface InventoryTotals {
        Long getTotalProducts();

        Long getTotalQuantity();

        BigDecimal getTotalValue();

        Long getLowStockProducts();

        Long getOutOfStockProducts();
    }

    interface CategoryTotals {
        String getCategory();

        Long getQuantity();

        BigDecimal getValue();
    }

    interface ProductValue {
        Long getId();

        String getName();

        String getCategory();

        Integer getQuantity();

        BigDecimal getTotalValue();
    }
}
