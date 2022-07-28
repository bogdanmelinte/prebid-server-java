package org.prebid.server.metric;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Contains cookie sync metrics for a bidders metrics support.
 */
public class CookieSyncMetrics extends UpdatableMetrics {

    private final Function<String, CookieSyncMetrics.BidderCookieSyncMetrics> bidderCookieSyncMetricsCreator;
    private final Map<String, CookieSyncMetrics.BidderCookieSyncMetrics> bidderCookieSyncMetrics;

    CookieSyncMetrics(MeterRegistry meterRegistry) {
        super(Objects.requireNonNull(meterRegistry), metricName -> "cookie_sync." + metricName);
        bidderCookieSyncMetricsCreator = bidder -> new BidderCookieSyncMetrics(meterRegistry, bidder);
        bidderCookieSyncMetrics = new HashMap<>();
    }

    CookieSyncMetrics.BidderCookieSyncMetrics forBidder(String bidder) {
        return bidderCookieSyncMetrics.computeIfAbsent(bidder, bidderCookieSyncMetricsCreator);
    }

    static class BidderCookieSyncMetrics extends UpdatableMetrics {

        private final TcfMetrics tcfMetrics;

        BidderCookieSyncMetrics(MeterRegistry meterRegistry, String bidder) {
            super(Objects.requireNonNull(meterRegistry),
                    nameCreator(Objects.requireNonNull(createCookieSyncPrefix(bidder))));
            tcfMetrics = new TcfMetrics(meterRegistry, createCookieSyncPrefix(bidder));
        }

        TcfMetrics tcf() {
            return tcfMetrics;
        }

        private static String createCookieSyncPrefix(String bidder) {
            return "cookie_sync." + bidder;
        }

        private static Function<MetricName, String> nameCreator(String prefix) {
            return metricName -> "%s.%s".formatted(prefix, metricName);
        }
    }
}

