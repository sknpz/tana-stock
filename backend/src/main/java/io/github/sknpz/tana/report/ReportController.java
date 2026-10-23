package io.github.sknpz.tana.report;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.sknpz.tana.product.ProductResponse;
import io.github.sknpz.tana.product.ProductService;
import io.github.sknpz.tana.shared.DateRange;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Relatórios")
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;
    private final ProductService productService;

    public ReportController(ReportService reportService, ProductService productService) {
        this.reportService = reportService;
        this.productService = productService;
    }

    @Operation(summary = "Indicadores do dashboard e valor do estoque por categoria")
    @GetMapping("/summary")
    public InventorySummaryResponse summary() {
        return reportService.summary();
    }

    @Operation(summary = "Inventário completo: todos os produtos ativos, ordenados por nome")
    @GetMapping("/inventory")
    public List<ProductResponse> inventory() {
        return productService.inventory();
    }

    @Operation(summary = "Total de entradas e saídas de estoque em um período")
    @GetMapping("/movements-summary")
    public MovementPeriodSummaryResponse movementsSummary(
            @Parameter(description = "Data inicial (yyyy-MM-dd)") @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @Parameter(description = "Data final, inclusiva (yyyy-MM-dd)") @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return reportService.movementsSummary(new DateRange(from, to));
    }

    @Operation(summary = "Produtos com maior valor em estoque (quantidade x preço unitário)")
    @GetMapping("/top-products")
    public List<TopProductResponse> topProducts(
            @Parameter(description = "Quantidade de produtos (1 a 50)") @RequestParam(defaultValue = "5") int limit) {
        return reportService.topProducts(limit);
    }
}
