package io.github.sknpz.tana.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.sknpz.tana.IntegrationTest;

/**
 * O banco é compartilhado com as outras classes de teste, então os totais são comparados antes e
 * depois de criar os dados do teste, em vez de esperar valores absolutos.
 */
class ReportApiTests extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private Map<String, Object> summary() throws Exception {
        return read(apiGet("/api/reports/summary").andExpect(status().isOk()), "$");
    }

    private static long number(Map<String, Object> json, String field) {
        return ((Number) json.get(field)).longValue();
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(value.toString());
    }

    @Test
    void summaryReflectsNewProductsAndMovements() throws Exception {
        Map<String, Object> before = summary();

        String category = unique("Relatório");
        Number categoryId = read(apiPost("""
                {"name": "%s"}
                """.formatted(category), "/api/categories"), "$.id");
        for (String product : List.of(
                // nome, preço, quantidade inicial, mínimo
                "\"OK\", \"unitPrice\": 10.00, \"initialQuantity\": 8, \"minStock\": 2",
                "\"Baixo\", \"unitPrice\": 4.00, \"initialQuantity\": 1, \"minStock\": 5",
                "\"Zerado\", \"unitPrice\": 99.00, \"initialQuantity\": 0, \"minStock\": 5")) {
            apiPost("""
                    {"name": %s, "categoryId": %s}
                    """.formatted(product, categoryId), "/api/products").andExpect(status().isCreated());
        }

        Map<String, Object> after = summary();
        assertThat(number(after, "totalProducts") - number(before, "totalProducts")).isEqualTo(3);
        assertThat(number(after, "totalQuantity") - number(before, "totalQuantity")).isEqualTo(9);
        assertThat(decimal(after.get("totalValue")).subtract(decimal(before.get("totalValue"))))
                .isEqualByComparingTo("84.00");
        assertThat(number(after, "lowStockProducts") - number(before, "lowStockProducts")).isEqualTo(1);
        assertThat(number(after, "outOfStockProducts") - number(before, "outOfStockProducts")).isEqualTo(1);
        assertThat(number(after, "categories") - number(before, "categories")).isEqualTo(1);
        // Só os produtos com quantidade inicial geram a entrada "Estoque inicial"
        assertThat(number(after, "movementsThisMonth") - number(before, "movementsThisMonth")).isEqualTo(2);

        List<Map<String, Object>> byCategory = read(apiGet("/api/reports/summary"),
                "$.valueByCategory[?(@.category == '" + category + "')]");
        assertThat(byCategory).hasSize(1);
        Map<String, Object> ours = byCategory.getFirst();
        assertThat(number(ours, "quantity")).isEqualTo(9);
        assertThat(decimal(ours.get("value"))).isEqualByComparingTo("84.00");
        BigDecimal expectedPercentage = new BigDecimal("8400")
                .divide(decimal(after.get("totalValue")), 1, RoundingMode.HALF_UP);
        assertThat(decimal(ours.get("percentage"))).isEqualByComparingTo(expectedPercentage);
    }

    @Test
    void topProductsAreOrderedByStockValue() throws Exception {
        String name = unique("Produto mais valioso");
        // 9.999.999,99 x 1.000 unidades: maior que qualquer outro produto criado pelos testes
        createProduct(name, "9999999.99", 1000, 0);

        apiGet("/api/reports/top-products?limit=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value(name))
                .andExpect(jsonPath("$[0].totalValue").value(9999999990.00));

        List<Object> values = read(apiGet("/api/reports/top-products?limit=10"), "$[*].totalValue");
        List<BigDecimal> totals = values.stream().map(ReportApiTests::decimal).toList();
        assertThat(totals).isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    void topProductsLimitIsKeptBetweenOneAndFifty() throws Exception {
        createProduct(unique("Limite"), "1", 1, 0);

        apiGet("/api/reports/top-products?limit=0").andExpect(jsonPath("$.length()").value(1));
        List<Object> all = read(apiGet("/api/reports/top-products?limit=500"), "$");
        assertThat(all).hasSizeLessThanOrEqualTo(50);
    }

    private void movement(long productId, String type, int quantity) throws Exception {
        apiPost("""
                {"type": "%s", "quantity": %d}
                """.formatted(type, quantity), "/api/products/{id}/movements", productId)
                .andExpect(status().isCreated());
    }

    @Test
    void inventoryListsOnlyActiveProductsSortedByName() throws Exception {
        String prefix = unique("Inv");
        createProduct(prefix + "-B", "2.00", 3, 1);
        createProduct(prefix + "-A", "1.00", 5, 1);
        long removed = createProduct(prefix + "-C", "1.00", 1, 1);
        apiDelete("/api/products/{id}", removed).andExpect(status().isNoContent());

        List<Map<String, Object>> ours = read(apiGet("/api/reports/inventory").andExpect(status().isOk()),
                "$[?(@.name =~ /" + prefix + ".*/)]");
        assertThat(ours).extracting(p -> p.get("name")).containsExactly(prefix + "-A", prefix + "-B");
        assertThat(decimal(ours.get(1).get("totalValue"))).isEqualByComparingTo("6.00");

        List<String> names = read(apiGet("/api/reports/inventory"), "$[*].name");
        assertThat(names).doesNotContain(prefix + "-C");
    }

    @Test
    void movementsSummaryCountsEntriesAndExitsInPeriod() throws Exception {
        // Período antigo e isolado: as movimentações dos outros testes ficam em 2026
        long id = createProduct(unique("Período"), "1.00", 10, 1); // entrada de 10
        movement(id, "EXIT", 3);
        movement(id, "EXIT", 2);
        movement(id, "ENTRY", 5);
        movement(id, "ENTRY", 7); // esta fica fora do período
        jdbc.update("UPDATE stock_movement SET created_at = '2001-03-10T15:00:00Z'::timestamptz WHERE product_id = ?",
                id);
        jdbc.update("""
                UPDATE stock_movement SET created_at = '2001-05-01T15:00:00Z'::timestamptz
                WHERE product_id = ? AND quantity = 7
                """, id);

        apiGet("/api/reports/movements-summary?from=2001-03-01&to=2001-03-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2001-03-01"))
                .andExpect(jsonPath("$.to").value("2001-03-31"))
                .andExpect(jsonPath("$.entries.count").value(2))
                .andExpect(jsonPath("$.entries.units").value(15))
                .andExpect(jsonPath("$.exits.count").value(2))
                .andExpect(jsonPath("$.exits.units").value(5));
    }

    @Test
    void movementsSummaryEmptyPeriodReturnsZeros() throws Exception {
        apiGet("/api/reports/movements-summary?from=1990-01-01&to=1990-01-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.count").value(0))
                .andExpect(jsonPath("$.entries.units").value(0))
                .andExpect(jsonPath("$.exits.count").value(0))
                .andExpect(jsonPath("$.exits.units").value(0));
    }

    @Test
    void movementsSummaryRequiresFromAndTo() throws Exception {
        apiGet("/api/reports/movements-summary?to=2026-01-31")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").exists());
        apiGet("/api/reports/movements-summary?from=2026-01-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void movementsSummaryFromAfterToReturns400() throws Exception {
        apiGet("/api/reports/movements-summary?from=2026-02-01&to=2026-01-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.to").value("A data final não pode ser anterior à inicial."));
    }

    @Test
    void newReportEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/reports/inventory")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/reports/movements-summary?from=2026-01-01&to=2026-01-31"))
                .andExpect(status().isUnauthorized());
    }
}
