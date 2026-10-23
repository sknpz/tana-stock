package io.github.sknpz.tana.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CnpjTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "11222333000181",       // numérico sem pontuação
            "11.222.333/0001-81",   // numérico com pontuação
            "12ABC34501DE35",       // alfanumérico (exemplo oficial da Receita Federal)
            "12.ABC.345/01DE-35",   // alfanumérico com pontuação
            "12abc34501de35"        // letras minúsculas são convertidas
    })
    void acceptsValidCnpj(String cnpj) {
        assertThat(Cnpj.isValid(cnpj)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "11222333000182",       // segundo dígito verificador errado
            "11222333000191",       // primeiro dígito verificador errado
            "1122233300018",        // 13 caracteres
            "112223330001811",      // 15 caracteres
            "12ABC34501DEAB",       // dígitos verificadores precisam ser números
            "00000000000000",       // passa no cálculo, mas é inválido
            "11111111111111",
            "11.222.333/0001-8!"    // caractere inválido
    })
    void rejectsInvalidCnpj(String cnpj) {
        assertThat(Cnpj.isValid(cnpj)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "./-"})
    void blankValuesAreNotValidCnpj(String cnpj) {
        assertThat(Cnpj.isValid(cnpj)).isFalse();
    }

    @Test
    void normalizeRemovesPunctuationAndUppercases() {
        assertThat(Cnpj.normalize(" 12.abc.345/01de-35 ")).isEqualTo("12ABC34501DE35");
    }

    @Test
    void normalizeTurnsBlankIntoNull() {
        assertThat(Cnpj.normalize(null)).isNull();
        assertThat(Cnpj.normalize("./- ")).isNull();
    }

    @Test
    void formatsNumericCnpj() {
        assertThat(Cnpj.format("11222333000181")).isEqualTo("11.222.333/0001-81");
    }

    @Test
    void formatsAlphanumericCnpj() {
        assertThat(Cnpj.format("12ABC34501DE35")).isEqualTo("12.ABC.345/01DE-35");
    }

    @Test
    void formatLeavesNullAndWrongLengthUntouched() {
        assertThat(Cnpj.format(null)).isNull();
        assertThat(Cnpj.format("123")).isEqualTo("123");
    }
}
