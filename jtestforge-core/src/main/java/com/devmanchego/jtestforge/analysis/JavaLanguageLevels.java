package com.devmanchego.jtestforge.analysis;

import com.github.javaparser.ParserConfiguration.LanguageLevel;

/** Maps a Java feature release to the JavaParser language level that parses it. */
public final class JavaLanguageLevels {

    private JavaLanguageLevels() {
    }

    /** Unknown (0) and anything newer than JavaParser 3.26 supports map to {@link LanguageLevel#JAVA_21}. */
    public static LanguageLevel forRelease(int release) {
        if (release <= 0 || release >= 21) {
            return LanguageLevel.JAVA_21;
        }
        if (release <= 4) {
            return LanguageLevel.JAVA_1_4;
        }
        return LanguageLevel.valueOf("JAVA_" + release);
    }
}
