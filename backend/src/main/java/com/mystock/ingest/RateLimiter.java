package com.mystock.ingest;

/** Simple token bucket: {@code perMinute} tokens per minute, burst up to {@code perMinute}. */
public final class RateLimiter {
    private final double ratePerNano;
    private final double capacity;
    private double tokens;
    private long last;

    public RateLimiter(int perMinute) {
        this.capacity = perMinute;
        this.ratePerNano = perMinute / 60_000_000_000.0;
        this.tokens = perMinute;
        this.last = System.nanoTime();
    }

    public void acquire() {
        while (true) {
            long waitNanos;
            synchronized (this) {
                long now = System.nanoTime();
                tokens = Math.min(capacity, tokens + (now - last) * ratePerNano);
                last = now;
                if (tokens >= 1) { tokens -= 1; return; }
                waitNanos = (long) ((1 - tokens) / ratePerNano);
            }
            try {
                Thread.sleep(Math.max(1, waitNanos / 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpstreamException("interrupted waiting for rate limiter");
            }
        }
    }
}
