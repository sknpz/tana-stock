package io.github.sknpz.tana.movement;

import java.io.IOException;
import java.time.LocalDate;

import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.sknpz.tana.shared.DateRange;
import io.github.sknpz.tana.shared.csv.CsvResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@Tag(name = "Movimentações")
@RestController
public class StockMovementController {

    private final StockMovementService movementService;

    private final ZoneId zoneId;

    public StockMovementController(StockMovementService movementService, @Value("${app.zone-id}") ZoneId zoneId) {
        this.movementService = movementService;
        this.zoneId = zoneId;
    }

    @Operation(summary = "Registra uma entrada ou saída de estoque",
            description = "Atualiza o saldo do produto e grava a movimentação no histórico. "
                    + "Saídas maiores que o saldo atual retornam 422.")
    @PostMapping("/api/products/{productId}/movements")
    @ResponseStatus(HttpStatus.CREATED)
    public StockMovementResponse register(@PathVariable Long productId,
            @Valid @RequestBody StockMovementRequest request) {
        return movementService.register(productId, request);
    }

    @GetMapping("/api/movements")
    public Page<StockMovementResponse> list(
            @RequestParam(required = false) MovementType type,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return movementService.list(type, productId, new DateRange(from, to), pageable);
    }

    @Operation(summary = "Exporta as movimentações filtradas em CSV")
    @GetMapping("/api/movements/export.csv")
    public void exportCsv(
            @RequestParam(required = false) MovementType type,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            HttpServletResponse response) throws IOException {
        DateRange period = new DateRange(from, to); // valida antes de abrir a resposta
        CsvResponses.prepare(response, "movimentacoes", zoneId);
        movementService.exportCsv(type, productId, period, response.getOutputStream());
    }
}
