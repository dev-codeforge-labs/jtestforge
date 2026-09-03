package com.acme;

import lombok.Data;

/**
 * Fixture: a Lombok {@code @Data} class with no explicit getters/setters written in
 * source. JavaParser parses source text only - it never runs annotation processors - so
 * the scanner must see zero declared methods here, not the getters/setters Lombok would
 * generate at compile time. lombok.Data is never an actual dependency of jtestforge
 * itself; the import alone is enough, since nothing here needs it resolved.
 */
@Data
public class ReportSettings {

    private String title;
    private boolean includeCharts;
}
