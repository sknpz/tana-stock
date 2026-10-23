package io.github.sknpz.tana.shared.csv;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import tools.jackson.databind.SequenceWriter;
import tools.jackson.dataformat.csv.CsvWriteFeature;
import tools.jackson.dataformat.csv.CsvMapper;
import tools.jackson.dataformat.csv.CsvSchema;

/**
 * Grava CSV no formato que o Excel brasileiro abre direto: BOM UTF-8, separador ";" e linhas
 * terminadas em CRLF. As linhas vão para a saída conforme chegam, sem montar o arquivo na memória.
 */
public final class CsvWriter implements Closeable, Flushable {

    private static final CsvMapper MAPPER = CsvMapper.builder()
            // Sem isso o Jackson coloca aspas em qualquer texto com espaço, vírgula etc.; só queremos
            // aspas quando há separador, aspas ou quebra de linha
            .enable(CsvWriteFeature.STRICT_CHECK_FOR_QUOTING)
            .build();
    private static final CsvSchema SCHEMA = CsvSchema.emptySchema()
            .withColumnSeparator(';')
            .withLineSeparator("\r\n");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final SequenceWriter sequence;

    private CsvWriter(SequenceWriter sequence) {
        this.sequence = sequence;
    }

    public static CsvWriter open(OutputStream out) {
        try {
            out.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            return new CsvWriter(MAPPER.writer(SCHEMA).writeValues(out));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Grava uma linha; {@code null} vira célula vazia. */
    public void row(String... values) {
        String[] cells = values.clone();
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == null) {
                cells[i] = "";
            }
        }
        sequence.write(cells);
    }

    /**
     * Texto livre vindo do usuário. Se começar com um caractere que o Excel interpreta como
     * fórmula, ganha o prefixo "'" (proteção contra injeção de fórmulas em planilhas).
     */
    public static String text(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return switch (value.charAt(0)) {
            case '=', '+', '-', '@', '\t', '\r' -> "'" + value;
            default -> value;
        };
    }

    /** Decimal com vírgula, duas casas e sem separador de milhar: {@code 1234,50}. */
    public static String decimal(BigDecimal value) {
        return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /** Data e hora no fuso da empresa: {@code dd/MM/yyyy HH:mm}. */
    public static String dateTime(Instant value, ZoneId zone) {
        return value == null ? "" : DATE_TIME.format(value.atZone(zone));
    }

    @Override
    public void flush() throws IOException {
        sequence.flush();
    }

    @Override
    public void close() throws IOException {
        sequence.close();
    }
}
