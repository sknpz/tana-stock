package io.github.sknpz.tana.report;

import java.time.LocalDate;

/** Totais de entradas e saídas de estoque em um período (usado pelo relatório em PDF). */
public record MovementPeriodSummaryResponse(LocalDate from, LocalDate to, MovementTotals entries,
        MovementTotals exits) {

    /** Número de movimentações e total de unidades movimentadas. */
    public record MovementTotals(long count, long units) {
    }
}
