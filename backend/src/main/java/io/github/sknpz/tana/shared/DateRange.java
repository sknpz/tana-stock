package io.github.sknpz.tana.shared;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import io.github.sknpz.tana.shared.exception.InvalidFieldException;

/** Período de datas com limites opcionais; {@code to} é inclusivo e as datas valem no fuso da empresa. */
public record DateRange(LocalDate from, LocalDate to) {

    public DateRange {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidFieldException("to", "A data final não pode ser anterior à inicial.");
        }
    }

    /** Início do dia {@code from} no fuso informado, ou null se não houver limite inferior. */
    public Instant startInclusive(ZoneId zone) {
        return from == null ? null : from.atStartOfDay(zone).toInstant();
    }

    /** Início do dia seguinte a {@code to} (limite exclusivo), ou null se não houver limite superior. */
    public Instant endExclusive(ZoneId zone) {
        return to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
    }
}
