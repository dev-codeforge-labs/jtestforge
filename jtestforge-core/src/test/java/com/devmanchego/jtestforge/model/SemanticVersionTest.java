package com.devmanchego.jtestforge.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticVersionTest {

    @Test
    void parsesAFullThreePartVersion() {
        assertThat(SemanticVersion.parse("6.2.1")).contains(new SemanticVersion(6, 2, 1));
    }

    @Test
    void parsesShortenedVersionsWithMissingPartsAsZero() {
        assertThat(SemanticVersion.parse("6.2")).contains(new SemanticVersion(6, 2, 0));
        assertThat(SemanticVersion.parse("7")).contains(new SemanticVersion(7, 0, 0));
    }

    @Test
    void ignoresQualifiersSoAPreReleaseComparesEqualToItsRelease() {
        // 6.2.0-RC1 genuinely has @MockitoBean; treating it as older than 6.2 would
        // select the wrong annotation and fail to compile.
        assertThat(SemanticVersion.parse("6.2.0-RC1")).contains(new SemanticVersion(6, 2, 0));
        assertThat(SemanticVersion.parse("3.4.0-SNAPSHOT")).contains(new SemanticVersion(3, 4, 0));
    }

    @Test
    void returnsEmptyForTextWithNoLeadingNumber() {
        assertThat(SemanticVersion.parse("RELEASE")).isEmpty();
        assertThat(SemanticVersion.parse("")).isEmpty();
        assertThat(SemanticVersion.parse(null)).isEmpty();
    }

    @Test
    void isAtLeastComparesMajorThenMinor() {
        SemanticVersion sixTwo = new SemanticVersion(6, 2, 0);

        assertThat(sixTwo.isAtLeast(6, 2)).isTrue();
        assertThat(sixTwo.isAtLeast(6, 1)).isTrue();
        assertThat(sixTwo.isAtLeast(5, 9)).isTrue();
        assertThat(sixTwo.isAtLeast(6, 3)).isFalse();
        assertThat(sixTwo.isAtLeast(7, 0)).isFalse();
    }

    @Test
    void ordersByMajorThenMinorThenPatch() {
        assertThat(new SemanticVersion(6, 2, 1)).isGreaterThan(new SemanticVersion(6, 2, 0));
        assertThat(new SemanticVersion(6, 10, 0)).isGreaterThan(new SemanticVersion(6, 9, 9));
        assertThat(new SemanticVersion(7, 0, 0)).isGreaterThan(new SemanticVersion(6, 99, 99));
    }
}
