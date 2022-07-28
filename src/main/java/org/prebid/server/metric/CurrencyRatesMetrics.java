package org.prebid.server.metric;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;
import java.util.function.Function;

/**
 * Circuit breaker metrics support.
 */
class CurrencyRatesMetrics extends UpdatableMetrics {

    private static final String SUFFIX = ".count";

    CurrencyRatesMetrics(MeterRegistry meterRegistry) {
        super(Objects.requireNonNull(meterRegistry), nameCreator());
    }

    private static Function<MetricName, String> nameCreator() {
        return metricName -> "currency-rates." + metricName + SUFFIX;
    }

    @Override
    void incCounter(MetricName metricName) {
        throw new UnsupportedOperationException();
    }

    @Override
    void incCounter(MetricName metricName, long value) {
        throw new UnsupportedOperationException();
    }

    @Override
    void updateTimer(MetricName metricName, long millis) {
        throw new UnsupportedOperationException();
    }

    @Override
    void updateHistogram(MetricName metricName, long value) {
        throw new UnsupportedOperationException();
    }
}
