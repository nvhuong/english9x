package com.english9x.billing.license;

import java.time.Instant;
import java.util.UUID;

/** What confirming or rejecting the order of a QR licence does to the licence (called by the order decision, in its transaction). */
public interface LicenseActivation {

    /**
     * The payment arrived: the licence becomes ACTIVE for its months from {@code at}. Records {@code license.change}.
     *
     * @throws IllegalStateException when the licence is not waiting for payment
     */
    void activate(UUID licenseId, Instant at);

    /** The payment was refused: the licence is CANCELLED. Records {@code license.change}. */
    void cancel(UUID licenseId);
}
