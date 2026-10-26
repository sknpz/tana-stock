package io.github.sknpz.tana.movement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import io.github.sknpz.tana.IntegrationTest;

class MovementCsvExportTests extends IntegrationTest {

    private static final String HEADER =
            "Data/hora;Tipo;Produto;SKU;Quantidade;Saldo anterior;Saldo novo;Observações;Usuário";

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${app.zone-id}")
    private ZoneId zone;

    private static List<String> lines(ResultActions result) throws Exception {
        String body = new String(result.andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿");
        return List.of(body.substring(1).split("\r\n"));
    }

    private long movementId(String type, int quantity, String notes, long productId) throws Exception {
        String json = apiPost("""
                {"type": "%s", "quantity": %d, "notes": "%s"}
                """.formatted(type, quantity, notes), "/api/products/{id}/movements", productId)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(json, "$.id").longValue();
    }

    @Test
    void exportsHeaderAndRow() throws Exception {
        String name = unique("Cabo");
        long id = createProduct(name, "2.00", 10, 1);
        String sku = jdbc.queryForObject("SELECT sku FROM product WHERE id = ?", String.class, id);
        long movementId = movementId("EXIT", 4, "Obra; A", id);
        jdbc.update("UPDATE stock_movement SET created_at = ?::timestamptz WHERE id = ?",
                "2026-03-05T15:30:00Z", movementId);

        LocalDate before = LocalDate.now(zone);

        ResultActions result = apiGet("/api/movements/export.csv?productId={id}&type=EXIT", id)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is("attachment; filename=\"movimentacoes-" + before + ".csv\""),
                        org.hamcrest.Matchers.is("attachment; filename=\"movimentacoes-" + LocalDate.now(zone) + ".csv\"")))); // cobre a virada da meia-noite

        String expectedDate = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .format(Instant.parse("2026-03-05T15:30:00Z").atZone(zone));
        assertThat(lines(result)).containsExactly(HEADER,
                expectedDate + ";Saída;" + name + ";" + (sku == null ? "" : sku)
                        + ";4;10;6;\"Obra; A\";Usuário USER");
    }

    @Test
    void entryTypeAndMovementWithoutUserExportEmptyUser() throws Exception {
        long id = createProduct(unique("Fio"), "1.00", 5, 1);
        jdbc.update("UPDATE stock_movement SET user_id = NULL WHERE product_id = ?", id);

        List<String> lines = lines(apiGet("/api/movements/export.csv?productId={id}", id));

        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).contains(";Entrada;").endsWith(";Estoque inicial;");
    }

    @Test
    void respectsTypeProductAndPeriod() throws Exception {
        long id = createProduct(unique("Tinta"), "3.00", 20, 1);
        long other = createProduct(unique("Cola"), "3.00", 20, 1);
        long old = movementId("EXIT", 1, "antiga", id);
        movementId("EXIT", 2, "recente", id);
        movementId("ENTRY", 3, "entrada", id);
        movementId("EXIT", 5, "outro produto", other);
        jdbc.update("UPDATE stock_movement SET created_at = ?::timestamptz WHERE id = ?",
                "2026-01-10T12:00:00Z", old);

        assertThat(lines(apiGet("/api/movements/export.csv?productId={id}", id))).hasSize(5);
        assertThat(lines(apiGet("/api/movements/export.csv?productId={id}&type=EXIT", id))).hasSize(3);

        List<String> period = lines(apiGet("/api/movements/export.csv?productId={id}&type=EXIT&from=2026-01-10&to=2026-01-10", id));
        assertThat(period).hasSize(2);
        assertThat(period.get(1)).contains(";antiga;");

        // Ordem: mais recente primeiro
        List<String> all = lines(apiGet("/api/movements/export.csv?productId={id}", id));
        assertThat(all.get(1)).contains(";entrada;");
        assertThat(all.get(4)).contains(";antiga;").doesNotContain("recente");
    }

    @Test
    void fromAfterToReturns400() throws Exception {
        apiGet("/api/movements/export.csv?from=2026-04-10&to=2026-04-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.to").value("A data final não pode ser anterior à inicial."));
    }

    @Test
    void invalidDateReturnsProblemDetails() throws Exception {
        apiGet("/api/movements/export.csv?from=2026-13-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").exists());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/movements/export.csv")).andExpect(status().isUnauthorized());
    }
}
