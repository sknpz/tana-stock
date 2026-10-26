package io.github.sknpz.tana.product;

import java.io.IOException;
import java.net.URI;
import java.time.ZoneId;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import io.github.sknpz.tana.shared.csv.CsvResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@Tag(name = "Produtos")
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final ZoneId zoneId;

    public ProductController(ProductService productService, @Value("${app.zone-id}") ZoneId zoneId) {
        this.productService = productService;
        this.zoneId = zoneId;
    }

    @GetMapping
    public Page<ProductResponse> list(
            @Parameter(description = "Busca por nome ou SKU") @RequestParam(required = false) String search,
            @RequestParam(required = false) StockStatus status,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long supplierId,
            @ParameterObject @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return productService.list(search, status, categoryId, supplierId, pageable);
    }

    @Operation(summary = "Exporta os produtos filtrados em CSV")
    @GetMapping("/export.csv")
    public void exportCsv(
            @Parameter(description = "Busca por nome ou SKU") @RequestParam(required = false) String search,
            @RequestParam(required = false) StockStatus status,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long supplierId,
            HttpServletResponse response) throws IOException {
        CsvResponses.prepare(response, "produtos", zoneId);
        productService.exportCsv(search, status, categoryId, supplierId, response.getOutputStream());
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return productService.get(id);
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductCreateRequest request) {
        ProductResponse created = productService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductUpdateRequest request) {
        return productService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
