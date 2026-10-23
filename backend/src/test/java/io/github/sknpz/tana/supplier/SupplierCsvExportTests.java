package io.github.sknpz.tana.supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.ResultActions;

import io.github.sknpz.tana.IntegrationTest;

class SupplierCsvExportTests extends IntegrationTest {

    private static final String HEADER = "Nome;CNPJ;Telefone;E-mail;Endereço;Produtos vinculados";

    @Value("${app.zone-id}")
    private ZoneId zone;

    private static List<String> lines(ResultActions result) throws Exception {
        String body = new String(result.andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿");
        return List.of(body.substring(1).split("\r\n"));
    }

    @Test
    void exportsHeaderAndFormattedCnpj() throws Exception {
        String name = unique("Forn");
        long id = ((Number) read(apiPost("""
                {"name": "%s", "cnpj": "11.222.333/0001-81", "phone": "1133334444",
                 "email": "a@b.com", "address": "Rua A, 10"}
                """.formatted(name), "/api/suppliers"), "$.id")).longValue();
        apiPost("""
                {"name": "%s", "sku": "%s", "unitPrice": 1, "initialQuantity": 1, "minStock": 1, "supplierId": %d}
                """.formatted(unique("P"), unique("sku").toUpperCase(), id), "/api/products")
                .andExpect(status().isCreated());

        ResultActions result = apiGet("/api/suppliers/export.csv?search={s}", name)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"fornecedores-" + LocalDate.now(zone) + ".csv\""));

        assertThat(lines(result)).containsExactly(HEADER,
                name + ";11.222.333/0001-81;1133334444;a@b.com;Rua A, 10;1");
    }

    @Test
    void supplierWithoutOptionalFieldsExportsEmptyCells() throws Exception {
        String name = unique("Simples");
        apiPost("{\"name\": \"%s\"}".formatted(name), "/api/suppliers").andExpect(status().isCreated());

        assertThat(lines(apiGet("/api/suppliers/export.csv?search={s}", name)).get(1))
                .isEqualTo(name + ";;;;;0");
    }

    @Test
    void respectsSearch() throws Exception {
        String prefix = unique("Busca");
        apiPost("{\"name\": \"%s A\"}".formatted(prefix), "/api/suppliers");
        apiPost("{\"name\": \"%s B\"}".formatted(prefix), "/api/suppliers");
        apiPost("{\"name\": \"%s\"}".formatted(unique("Outro")), "/api/suppliers");

        List<String> lines = lines(apiGet("/api/suppliers/export.csv?search={s}", prefix + " B"));
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).startsWith(prefix + " B;");
        assertThat(lines(apiGet("/api/suppliers/export.csv?search={s}", prefix))).hasSize(3);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/suppliers/export.csv")).andExpect(status().isUnauthorized());
    }
}
