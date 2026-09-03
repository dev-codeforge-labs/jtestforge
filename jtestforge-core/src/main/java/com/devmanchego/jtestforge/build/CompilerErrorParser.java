package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.CompilerError;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts javac diagnostics from {@code maven-compiler-plugin}'s console output —
 * jtestforge-specification.md §13.1.
 *
 * <p>A primary diagnostic line looks like:
 * <pre>[ERROR] /path/Foo.java:[12,34] cannot find symbol</pre>
 * followed, in real Maven output, by further {@code [ERROR]}-prefixed continuation lines
 * carrying javac's own {@code symbol:}/{@code location:} detail:
 * <pre>
 * [ERROR]   symbol:   variable bar
 * [ERROR]   location: class com.acme.Foo
 * </pre>
 * Those continuation lines are folded into the same {@link CompilerError}'s message,
 * since without them the diagnostic is frequently unusable - "cannot find symbol" alone
 * does not say which symbol.
 */
public final class CompilerErrorParser {

    private static final Pattern PRIMARY_LINE =
            Pattern.compile("^\\[ERROR]\\s+(.+\\.java):\\[(\\d+),(\\d+)]\\s*(.*)$");
    private static final Pattern ERROR_PREFIXED_LINE = Pattern.compile("^\\[ERROR]\\s?(.*)$");
    private static final String COMPILATION_HEADER = "COMPILATION ERROR";
    private static final String HELP_FOOTER = "-> [Help";

    private CompilerErrorParser() {
    }

    public static List<CompilerError> parse(String mavenOutput) {
        List<CompilerError> errors = new ArrayList<>();
        if (mavenOutput == null || mavenOutput.isBlank()) {
            return errors;
        }

        String[] lines = mavenOutput.split("\r?\n", -1);
        int index = 0;
        while (index < lines.length) {
            Matcher primary = PRIMARY_LINE.matcher(lines[index]);
            if (!primary.matches()) {
                index++;
                continue;
            }

            String file = primary.group(1);
            int line = Integer.parseInt(primary.group(2));
            int column = Integer.parseInt(primary.group(3));
            StringBuilder message = new StringBuilder(primary.group(4));
            index++;

            while (index < lines.length) {
                Matcher continuation = ERROR_PREFIXED_LINE.matcher(lines[index]);
                if (!continuation.matches()) {
                    break;
                }
                String detail = continuation.group(1);
                if (PRIMARY_LINE.matcher(lines[index]).matches()
                        || detail.contains(COMPILATION_HEADER)
                        || detail.startsWith(HELP_FOOTER)) {
                    break;
                }
                if (!detail.isBlank()) {
                    message.append('\n').append(detail);
                }
                index++;
            }

            errors.add(new CompilerError(file, line, column, message.toString()));
        }
        return List.copyOf(errors);
    }
}
