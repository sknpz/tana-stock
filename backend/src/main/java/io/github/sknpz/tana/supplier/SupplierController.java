package io.github.sknpz.tana.supplier;

import java.io.IOException;
import java.net.URI;
import java.time.ZoneId;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
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

@Tag(name = "Fornecedores")
@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private final SupplierService supplierService;

    private final ZoneId zoneId;

    public SupplierController(SupplierService supplierService, @Value("${app.zone-id}") ZoneId zoneId) {
        this.supplierService = supplierService;
        this.zoneId = zoneId;
    }

    @GetMapping
    public List<SupplierResponse> list(
            @Parameter(description = "Busca por nome, CNPJ ou e-mail") @RequestParam(required = false) String search) {
        return supplierService.list(search);
    }

    @Operation(summary = "Exporta os fornecedores filtrados em CSV")
    @GetMapping("/export.csv")
    public void exportCsv(
            @Parameter(description = "Busca por nome, CNPJ ou e-mail") @RequestParam(required = false) String search,
            HttpServletResponse response) throws IOException {
        CsvResponses.prepare(response, "fornecedores", zoneId);
        supplierService.exportCsv(search, response.getOutputStream());
    }

    @GetMapping("/{id}")
    public SupplierResponse get(@PathVariable Long id) {
        return supplierService.get(id);
    }

    @PostMapping
    public ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierRequest request) {
        SupplierResponse created = supplierService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public SupplierResponse update(@PathVariable Long id, @Valid @RequestBody SupplierRequest request) {
        return supplierService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        supplierService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
