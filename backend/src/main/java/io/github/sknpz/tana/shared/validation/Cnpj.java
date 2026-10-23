package io.github.sknpz.tana.shared.validation;

import java.util.regex.Pattern;

/**
 * Normalização e validação de CNPJ, incluindo o formato alfanumérico
 * (12 caracteres [0-9A-Z] + 2 dígitos verificadores) adotado pela Receita Federal em 07/2026.
 *
 * O cálculo dos dígitos verificadores é o mesmo do CNPJ numérico; cada caractere
 * vale seu código ASCII menos 48 ('0' = 0 ... '9' = 9, 'A' = 17 ... 'Z' = 42).
 */
public final class Cnpj {

    private static final Pattern FORMAT = Pattern.compile("[0-9A-Z]{12}[0-9]{2}");
    private static final int[] WEIGHTS_FIRST = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] WEIGHTS_SECOND = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    private Cnpj() {
    }

    /** Remove pontuação e espaços e converte para maiúsculas: "11.222.333/0001-81" → "11222333000181". */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("[.\\-/\\s]", "").toUpperCase();
        return normalized.isEmpty() ? null : normalized;
    }

    /** Aplica a máscara a um CNPJ já normalizado: "11222333000181" → "11.222.333/0001-81". Outros tamanhos voltam como estão. */
    public static String format(String normalized) {
        if (normalized == null || normalized.length() != 14) {
            return normalized;
        }
        return normalized.substring(0, 2) + "." + normalized.substring(2, 5) + "." + normalized.substring(5, 8)
                + "/" + normalized.substring(8, 12) + "-" + normalized.substring(12);
    }

    public static boolean isValid(String value) {
        String cnpj = normalize(value);
        if (cnpj == null || !FORMAT.matcher(cnpj).matches()) {
            return false;
        }
        if (cnpj.chars().distinct().count() == 1) {
            return false; // 00000000000000, 11111111111111... passam no cálculo mas são inválidos
        }
        int first = checkDigit(cnpj, WEIGHTS_FIRST);
        int second = checkDigit(cnpj, WEIGHTS_SECOND);
        return cnpj.charAt(12) - '0' == first && cnpj.charAt(13) - '0' == second;
    }

    private static int checkDigit(String cnpj, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (cnpj.charAt(i) - '0') * weights[i];
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }
}
