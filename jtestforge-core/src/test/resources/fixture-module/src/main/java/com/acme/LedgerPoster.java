package com.acme;

import org.slf4j.Logger;

/**
 * Fixture: field injection (no constructor at all) - the collaborator must come from the
 * {@code @Autowired}-annotated field, not be missed just because there is no constructor
 * to inspect.
 */
public class LedgerPoster {

    @Autowired
    private Logger logger;

    private String label = "default";

    public void post(String entry) {
        logger.info(entry);
    }
}
