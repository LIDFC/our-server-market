package site.vinoff.market.core.port;

import java.time.Instant;

/** Time, so tests can move it. */
@FunctionalInterface
public interface MarketClock {

    Instant now();

    static MarketClock system() {
        return Instant::now;
    }
}
