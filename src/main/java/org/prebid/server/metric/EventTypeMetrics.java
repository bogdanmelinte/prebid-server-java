package org.prebid.server.metric;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.function.Function;

/**
 * Metrics for reporting on certain event type
 */
public class EventTypeMetrics extends UpdatableMetrics {

    EventTypeMetrics(MeterRegistry meterRegistry, String prefix, MetricName eventType) {
        super(meterRegistry, nameCreator(prefix, eventType));
    }

    private static Function<MetricName, String> nameCreator(String prefix, MetricName eventType) {
        return metricName -> "%s.%s.%s".formatted(prefix, eventType, metricName);
    }
}
