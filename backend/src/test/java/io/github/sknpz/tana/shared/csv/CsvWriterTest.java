package io.github.sknpz.tana.shared.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CsvWriterTest {

    private static String write(String... row) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CsvWriter writer = CsvWriter.open(out)) {
            writer.row(row);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void startsWithBomAndUsesSemicolonAndCrlf() throws Exception {
        String csv = write("a", "b", null);

        assertThat(csv).isEqualTo("﻿a;b;\r\n");
    }

    @Test
    void csvEscapesSeparatorsQuotesAndLineBreaks() throws Exception {
        String csv = write("Parafuso; \"1/4\"\nlongo", "ok");

        assertThat(csv).isEqualTo("﻿\"Parafuso; \"\"1/4\"\"\nlongo\";ok\r\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"=", "+", "-", "@", "\t", "\r"})
    void guardsFormulaPrefixes(String prefix) {
        assertThat(CsvWriter.text(prefix + "1+1")).isEqualTo("'" + prefix + "1+1");
    }

    @Test
    void textLeavesSafeValuesAndNullAlone() {
        assertThat(CsvWriter.text("Parafuso")).isEqualTo("Parafuso");
        assertThat(CsvWriter.text(null)).isEmpty();
        assertThat(CsvWriter.text("")).isEmpty();
    }

    @Test
    void decimalUsesCommaWithoutThousands() {
        assertThat(CsvWriter.decimal(new BigDecimal("1234.5"))).isEqualTo("1234,50");
        assertThat(CsvWriter.decimal(null)).isEmpty();
    }

    @Test
    void dateTimeUsesZone() {
        Instant instant = Instant.parse("2026-10-23T00:50:00Z");

        assertThat(CsvWriter.dateTime(instant, ZoneId.of("America/Sao_Paulo"))).isEqualTo("22/10/2026 21:50");
        assertThat(CsvWriter.dateTime(null, ZoneId.of("America/Sao_Paulo"))).isEmpty();
    }
}
