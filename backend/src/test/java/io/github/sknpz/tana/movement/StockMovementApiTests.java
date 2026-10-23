package io.github.sknpz.tana.movement;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.sknpz.tana.IntegrationTest;

class StockMovementApiTests extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private static String movement(String type, int quantity, String notes) {
        return """
                {"type": "%s", "quantity": %d, "notes": %s}
                """.formatted(type, quantity, notes == null ? "null" : "\"" + notes + "\"");
    }

    @Test
    void entriesAndExitsUpdateTheBalance() throws Exception {
        long id = createProduct(unique("Cimento"), "35.00", 10, 2);

        apiPost(movement("ENTRY", 5, "Compra NF 123"), "/api/products/{id}/movements", id)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.previousQuantity").value(10))
                .andExpect(jsonPath("$.newQuantity").value(15))
                .andExpect(jsonPath("$.notes").value("Compra NF 123"))
                .andExpect(jsonPath("$.product.id").value(id));
        apiPost(movement("EXIT", 15, null), "/api/products/{id}/movements", id)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.newQuantity").value(0));

        apiGet("/api/products/{id}", id)
                .andExpect(jsonPath("$.quantity").value(0))
                .andExpect(jsonPath("$.stockStatus").value("OUT_OF_STOCK"));
    }

    @Test
    void exitAboveBalanceIsUnprocessableAndChangesNothing() throws Exception {
        long id = createProduct(unique("Areia"), "5.00", 3, 1);

        apiPost(movement("EXIT", 4, null), "/api/products/{id}/movements", id)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail", containsString("tem 3 unidade(s) e a saída solicitada é de 4")));

        apiGet("/api/products/{id}", id).andExpect(jsonPath("$.quantity").value(3));
        apiGet("/api/movements?productId={id}", id).andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void invalidMovementsAreFieldErrors() throws Exception {
        long id = createProduct(unique("Brita"), "5.00", 3, 1);

        apiPost("""
                {"quantity": 0}
                """, "/api/products/{id}/movements", id)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.type").value("O tipo é obrigatório (ENTRY ou EXIT)."))
                .andExpect(jsonPath("$.errors.quantity").value("A quantidade deve ser maior que zero."));
    }

    @Test
    void historyIsNewestFirstAndFiltersByType() throws Exception {
        long id = createProduct(unique("Tijolo"), "1.20", 100, 10);
        apiPost(movement("EXIT", 30, "Obra 1"), "/api/products/{id}/movements", id);
        apiPost(movement("EXIT", 20, "Obra 2"), "/api/products/{id}/movements", id);

        apiGet("/api/movements?productId={id}", id)
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].notes").value("Obra 2"))
                .andExpect(jsonPath("$.content[2].notes").value("Estoque inicial"));
        apiGet("/api/movements?productId={id}&type=EXIT", id)
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].type", everyItem(is("EXIT"))));
    }

    @Test
    void movementOnUnknownProductIsNotFound() throws Exception {
        apiPost(movement("ENTRY", 1, null), "/api/products/{id}/movements", 999999)
                .andExpect(status().isNotFound());
    }

    // Ajusta created_at direto no banco: a API sempre grava "agora"
    private void setCreatedAt(long movementId, String instant) {
        jdbc.update("UPDATE stock_movement SET created_at = ?::timestamptz WHERE id = ?", instant, movementId);
    }

    private long movementId(String body, long productId) throws Exception {
        String json = apiPost(body, "/api/products/{id}/movements", productId)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.<Number>read(json, "$.id").longValue();
    }

    @Test
    void periodBoundariesFollowCompanyZone() throws Exception {
        long id = createProduct(unique("Telha"), "2.00", 100, 1);
        long first = movementId(movement("ENTRY", 1, "Dentro"), id);
        long second = movementId(movement("ENTRY", 2, "Fora"), id);
        setCreatedAt(first, "2026-03-10T02:30:00Z"); // 09/03 23:30 em São Paulo
        setCreatedAt(second, "2026-03-10T03:00:00Z"); // 10/03 00:00 em São Paulo

        apiGet("/api/movements?from=2026-03-01&to=2026-03-09&productId={id}", id)
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].notes").value("Dentro"));
    }

    @Test
    void periodCombinesWithType() throws Exception {
        long id = createProduct(unique("Vergalhão"), "9.00", 100, 1);
        long entry = movementId(movement("ENTRY", 1, "Entrada"), id);
        long exit = movementId(movement("EXIT", 1, "Saída"), id);
        setCreatedAt(entry, "2026-04-05T12:00:00Z");
        setCreatedAt(exit, "2026-04-05T13:00:00Z");

        apiGet("/api/movements?productId={id}&type=EXIT&from=2026-04-05&to=2026-04-05", id)
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].notes").value("Saída"));
    }

    @Test
    void fromAfterToReturns400WithFieldError() throws Exception {
        apiGet("/api/movements?from=2026-04-10&to=2026-04-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.errors.to").value("A data final não pode ser anterior à inicial."));
    }

    @Test
    void invalidDateFormatReturns400() throws Exception {
        apiGet("/api/movements?from=2026-13-01").andExpect(status().isBadRequest());
    }
}
