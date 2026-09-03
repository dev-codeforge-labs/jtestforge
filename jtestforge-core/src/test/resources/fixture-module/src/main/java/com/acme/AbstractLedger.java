package com.acme;

/** Fixture: an abstract class, never a scan candidate. */
public abstract class AbstractLedger {
    public abstract void post(String entry);
}
