package io.github.sknpz.tana.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.ResultActions;

import io.github.sknpz.tana.IntegrationTest;
import jakarta.persistence.EntityManager;

class ProductCsvExportTests extends IntegrationTest {

    private static final String HEADER =
            "SKU;Produto;Categoria;Fornecedor;Preço unitário;Quantidade;Estoque mínimo;Valor em estoque;Situação";

    @Autowired
    private ProductService productService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${app.zone-id}")
    private ZoneId zone;

    /** Linhas do CSV (sem o BOM e sem a linha vazia final). */
    private static List<String> lines(ResultActions result) throws Exception {
        String body = new String(result.andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿");
        return List.of(body.substring(1).split("\r\n"));
    }

    @Test
    void exportsHeaderAndFormattedRow() throws Exception {
        String name = unique("Parafuso");
        String category = unique("Cat");
        String supplier = unique("Forn");
        long categoryId = ((Number) read(apiPost("{\"name\": \"%s\"}".formatted(category), "/api/categories"),
                "$.id")).longValue();
        long supplierId = ((Number) read(apiPost("{\"name\": \"%s\", \"cnpj\": \"%s\"}".formatted(supplier,
                randomCnpj()), "/api/suppliers"), "$.id")).longValue();
        String sku = unique("sku").toUpperCase();
        apiPost("""
                {"name": "%s", "sku": "%s", "unitPrice": 12.5, "initialQuantity": 3, "minStock": 5,
                 "categoryId": %d, "supplierId": %d}
                """.formatted(name, sku, categoryId, supplierId), "/api/products").andExpect(status().isCreated());

        LocalDate before = LocalDate.now(zone);

        ResultActions result = apiGet("/api/products/export.csv?search={s}", name)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is("attachment; filename=\"produtos-" + before + ".csv\""),
                        org.hamcrest.Matchers.is("attachment; filename=\"produtos-" + LocalDate.now(zone) + ".csv\"")))); // cobre a virada da meia-noite

        assertThat(lines(result)).containsExactly(HEADER,
                sku + ";" + name + ";" + category + ";" + supplier + ";12,50;3;5;37,50;Estoque baixo");
    }

    @Test
    void exposesContentDispositionToBrowsers() throws Exception {
        mockMvc.perform(get("/api/products/export.csv")
                        .header("Origin", "http://localhost:5500")
                        .header(AUTHORIZATION, "Bearer " + userToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Content-Disposition")));
    }

    @Test
    void respectsSearchAndStatusFilters() throws Exception {
        String prefix = unique("Filtro");
        createProduct(prefix + " cheio", "1", 50, 5);
        createProduct(prefix + " zerado", "1", 0, 5);

        assertThat(lines(apiGet("/api/products/export.csv?search={s}", prefix))).hasSize(3);

        List<String> outOfStock = lines(apiGet("/api/products/export.csv?search={s}&status=OUT_OF_STOCK", prefix));
        assertThat(outOfStock).hasSize(2);
        assertThat(outOfStock.get(1)).contains(prefix + " zerado").endsWith(";Sem estoque");

        List<String> ok = lines(apiGet("/api/products/export.csv?search={s}&status=OK", prefix));
        assertThat(ok).hasSize(2);
        assertThat(ok.get(1)).contains(prefix + " cheio").endsWith(";OK");
    }

    @Test
    void productWithoutCategoryOrSupplierExportsEmptyCells() throws Exception {
        String name = unique("Avulso");
        createProduct(name, "2", 20, 5);

        assertThat(lines(apiGet("/api/products/export.csv?search={s}", name)).get(1))
                .isEqualTo(";" + name + ";;;2,00;20;5;40,00;OK");
    }

    @Test
    void formulaInProductNameIsNeutralized() throws Exception {
        String marker = unique("f");
        createProduct("=HYPERLINK(\\\"x\\\")" + marker, "1", 20, 5);

        List<String> lines = lines(apiGet("/api/products/export.csv?search={s}", marker));
        assertThat(lines.get(1)).startsWith(";\"'=HYPERLINK(\"\"x\"\")");
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/products/export.csv")).andExpect(status().isUnauthorized());
    }

    @Test
    void exportDoesNotKeepEntitiesInPersistenceContext() throws Exception {
        String name = unique("Memoria");
        createProduct(name + " a", "1", 20, 5);
        createProduct(name + " b", "1", 20, 5);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        // Mesma transação para o serviço e para a conferência do contexto de persistência
        Long managed = new TransactionTemplate(transactionManager).execute(status -> {
            productService.exportCsv(name, null, null, null, out);
            return entityManager.unwrap(Session.class).getStatistics().getEntityCount() + 0L;
        });

        assertThat(out.toString(StandardCharsets.UTF_8).split("\r\n")).hasSize(3);
        assertThat(managed).isZero();
    }
}
