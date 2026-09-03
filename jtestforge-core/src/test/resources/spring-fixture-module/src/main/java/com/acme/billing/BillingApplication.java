package com.acme.billing;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Required by every Boot slice annotation: @WebMvcTest and friends search enclosing
 * packages for a @SpringBootConfiguration and fail outright without one. A target module
 * JTestForge generates slice tests for must therefore have one - which real Boot
 * applications always do.
 */
@SpringBootApplication
public class BillingApplication {
}
