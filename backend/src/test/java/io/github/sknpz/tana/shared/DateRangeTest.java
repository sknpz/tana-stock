package io.github.sknpz.tana.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import io.github.sknpz.tana.shared.exception.InvalidFieldException;

class DateRangeTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    @Test
    void startAndEndUseCompanyZone() {
        DateRange range = new DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 22));

        assertThat(range.startInclusive(SAO_PAULO)).isEqualTo(Instant.parse("2026-10-01T03:00:00Z"));
        assertThat(range.endExclusive(SAO_PAULO)).isEqualTo(Instant.parse("2026-10-23T03:00:00Z"));
    }

    @Test
    void nullBoundsStayOpen() {
        DateRange range = new DateRange(null, null);

        assertThat(range.startInclusive(SAO_PAULO)).isNull();
        assertThat(range.endExclusive(SAO_PAULO)).isNull();
    }

    @Test
    void fromAfterToIsRejected() {
        assertThatThrownBy(() -> new DateRange(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 1)))
                .isInstanceOfSatisfying(InvalidFieldException.class, ex -> {
                    assertThat(ex.field()).isEqualTo("to");
                    assertThat(ex.getMessage()).isEqualTo("A data final não pode ser anterior à inicial.");
                });
    }
}
