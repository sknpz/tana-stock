package io.github.sknpz.tana.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.sknpz.tana.report.InventorySummaryResponse.CategoryValue;
import io.github.sknpz.tana.report.MovementPeriodSummaryResponse.MovementTotals;
import io.github.sknpz.tana.report.ReportRepository.InventoryTotals;
import io.github.sknpz.tana.report.ReportRepository.MovementTotalsRow;
import io.github.sknpz.tana.shared.DateRange;

@Service
public class ReportService {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final int MAX_TOP_PRODUCTS = 50;

    private final ReportRepository reportRepository;
    private final ZoneId zoneId;

    public ReportService(ReportRepository reportRepository, @Value("${app.zone-id}") ZoneId zoneId) {
        this.reportRepository = reportRepository;
        this.zoneId = zoneId;
    }

    @Transactional(readOnly = true)
    public InventorySummaryResponse summary() {
        InventoryTotals totals = reportRepository.inventoryTotals();
        BigDecimal totalValue = totals.getTotalValue();
        long totalProducts = totals.getTotalProducts();

        BigDecimal average = totalProducts == 0 ? BigDecimal.ZERO
                : totalValue.divide(BigDecimal.valueOf(totalProducts), 2, RoundingMode.HALF_UP);

        List<CategoryValue> byCategory = reportRepository.valueByCategory().stream()
                .map(c -> new CategoryValue(c.getCategory(), c.getQuantity(), c.getValue(),
                        percentage(c.getValue(), totalValue)))
                .toList();

        // "Este mês" segue o fuso da empresa, não o do servidor (que em nuvem costuma ser UTC)
        Instant startOfMonth = LocalDate.now(zoneId).withDayOfMonth(1).atStartOfDay(zoneId).toInstant();

        return new InventorySummaryResponse(totalProducts, totals.getTotalQuantity(), totalValue, average,
                totals.getLowStockProducts(), totals.getOutOfStockProducts(), reportRepository.countCategories(),
                reportRepository.countMovementsSince(startOfMonth), byCategory);
    }

    @Transactional(readOnly = true)
    public List<TopProductResponse> topProducts(int limit) {
        return reportRepository.mostValuableProducts(Math.clamp(limit, 1, MAX_TOP_PRODUCTS)).stream()
                .map(p -> new TopProductResponse(p.getId(), p.getName(), p.getCategory(), p.getQuantity(),
                        p.getTotalValue()))
                .toList();
    }

    /** Totais de entradas e saídas no período; ambos os limites são obrigatórios neste relatório. */
    @Transactional(readOnly = true)
    public MovementPeriodSummaryResponse movementsSummary(DateRange period) {
        MovementTotalsRow row = reportRepository.movementTotals(period.startInclusive(zoneId),
                period.endExclusive(zoneId));
        return new MovementPeriodSummaryResponse(period.from(), period.to(),
                new MovementTotals(row.getEntryCount(), row.getEntryUnits()),
                new MovementTotals(row.getExitCount(), row.getExitUnits()));
    }

    private static BigDecimal percentage(BigDecimal part, BigDecimal total) {
        if (total.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(ONE_HUNDRED).divide(total, 1, RoundingMode.HALF_UP);
    }
}
