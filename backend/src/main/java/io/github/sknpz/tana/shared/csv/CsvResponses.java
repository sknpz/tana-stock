package io.github.sknpz.tana.shared.csv;

import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.http.HttpHeaders;

import jakarta.servlet.http.HttpServletResponse;

/** Cabeçalhos das respostas de exportação CSV. */
public final class CsvResponses {

    private CsvResponses() {
    }

    /** Define o tipo do conteúdo e o nome do arquivo ({@code <baseName>-<yyyy-MM-dd>.csv}, data no fuso). */
    public static void prepare(HttpServletResponse response, String baseName, ZoneId zone) {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + baseName + "-" + LocalDate.now(zone) + ".csv\"");
    }
}
