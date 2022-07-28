package org.prebid.server.metric;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Contains user sync metrics for a bidders metrics support.
 */
class UserSyncMetrics extends UpdatableMetrics {

    private final Function<String, BidderUserSyncMetrics> bidderUserSyncMetricsCreator;
    // not thread-safe maps are intentionally used here because it's harmless in this particular case - eventually
    // this all boils down to metrics lookup by underlying metric registry and that operation is guaranteed to be
    // thread-safe
    private final Map<String, BidderUserSyncMetrics> bidderUserSyncMetrics;

    UserSyncMetrics(MeterRegistry meterRegistry) {
        super(Objects.requireNonNull(meterRegistry), metricName -> "usersync." + metricName);
        bidderUserSyncMetricsCreator = bidder -> new BidderUserSyncMetrics(meterRegistry, bidder);
        bidderUserSyncMetrics = new HashMap<>();
    }

    BidderUserSyncMetrics forBidder(String bidder) {
        return bidderUserSyncMetrics.computeIfAbsent(bidder, bidderUserSyncMetricsCreator);
    }

    static class BidderUserSyncMetrics extends UpdatableMetrics {

        private final TcfMetrics tcfMetrics;

        BidderUserSyncMetrics(MeterRegistry meterRegistry, String bidder) {
            super(Objects.requireNonNull(meterRegistry),
                    nameCreator(Objects.requireNonNull(createUserSyncPrefix(bidder))));
            tcfMetrics = new TcfMetrics(meterRegistry, createUserSyncPrefix(bidder));
        }

        TcfMetrics tcf() {
            return tcfMetrics;
        }

        private static String createUserSyncPrefix(String bidder) {
            return "usersync." + bidder;
        }

        private static Function<MetricName, String> nameCreator(String prefix) {
            return metricName -> "%s.%s".formatted(prefix, metricName);
        }
    }
}
