package dev.trakr.kernel;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

// BLOCK 1: class shape
/** Time-ordered UUIDv7 ids (RFC 9562), generated in the application. */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ReentrantLock LOCK = new ReentrantLock();

    // guarded by LOCK: last timestamp used and the 12-bit counter within that millisecond
    private static long lastMillis = -1;
    private static int counter;

    private Ids() {}

    // BLOCK 2: newId()
    /** A new UUIDv7; ids from this JVM are strictly increasing. */
    public static UUID newId() {
        long millis;
        int seq;
        LOCK.lock();
        try {
            long now = System.currentTimeMillis();
            if (now > lastMillis) {
                lastMillis = now;
                counter = 0;
            } else {
                // same millisecond, or the clock went backwards: stay on lastMillis and count up
                counter++;
                if (counter > 0xFFF) {
                    // 4096 ids in one millisecond: borrow the next millisecond
                    lastMillis++;
                    counter = 0;
                }
            }
            millis = lastMillis;
            seq = counter;
        } finally {
            LOCK.unlock();
        }

        // msb: 48-bit unix_ts_ms | 4-bit version 0111 | 12-bit rand_a (our counter)
        long msb = ((millis & 0xFFFF_FFFF_FFFFL) << 16) | (0x7L << 12) | seq;
        // lsb: 2-bit variant 10 | 62 random bits
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }

    // BLOCK 3: timestampOf(UUID)
    /** The creation time embedded in a UUIDv7, to the millisecond. */
    public static Instant timestampOf(UUID id) {
        if (id.version() != 7) {
            throw new IllegalArgumentException("not a UUIDv7: " + id);
        }
        return Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16);
    }
}
