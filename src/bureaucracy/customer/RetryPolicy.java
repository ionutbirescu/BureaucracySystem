package bureaucracy.customer;

import java.util.concurrent.ThreadLocalRandom;

public record RetryPolicy(int maxAttempts, long initialBackoffMs, long maxBackoffMs, long attemptTimeoutMs){
    public static final RetryPolicy DEFAULT = new RetryPolicy(3, 100, 1000, 5000);

    public long backoffMs(int failedAttempts) {
        long delay = initialBackoffMs;
        for(int i=1; i<failedAttempts && delay < maxBackoffMs; i++) {
            delay *= 2; // we double the delay as we can afford to wait longer
        }
        delay = Math.min(delay, maxBackoffMs); // we cap the delay at maxBackoffMs
        return delay / 2 + ThreadLocalRandom.current().nextLong(delay/2 + 1);
        // ThreadLocalRandom gives each thread its own generator, so
        //customers retrying at the same moment don't wait on a shared Random
    }

}
