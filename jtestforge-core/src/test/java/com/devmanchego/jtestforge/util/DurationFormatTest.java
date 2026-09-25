package com.devmanchego.jtestforge.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DurationFormatTest {

    @Test
    void underASecondShowsOnlyMilliseconds() {
        assertThat(DurationFormat.humanReadable(577)).isEqualTo("577ms");
    }

    @Test
    void underAMinuteShowsSecondsAndMilliseconds() {
        assertThat(DurationFormat.humanReadable(10_577)).isEqualTo("10s 577ms");
    }

    @Test
    void overAMinuteShowsMinutesSecondsAndMilliseconds() {
        assertThat(DurationFormat.humanReadable(130_577)).isEqualTo("2m 10s 577ms");
    }

    @Test
    void exactlyZeroShowsZeroMilliseconds() {
        assertThat(DurationFormat.humanReadable(0)).isEqualTo("0ms");
    }

    @Test
    void wholeMinutesWithNoLeftoverSecondsStillPrintsZeroSeconds() {
        assertThat(DurationFormat.humanReadable(120_000)).isEqualTo("2m 0s 0ms");
    }
}
