package org.prebid.server.metric;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;
import java.util.function.Function;

/**
 * Request metrics support.
 */
class ValidationMetrics extends UpdatableMetrics {

    private final SpecificValidationMetrics sizeValidationMetrics;
    private final SpecificValidationMetrics secureValidationMetrics;

    ValidationMetrics(MeterRegistry meterRegistry, String prefix) {
        super(Objects.requireNonNull(meterRegistry),
                nameCreator(createPrefix(Objects.requireNonNull(prefix))));

        sizeValidationMetrics = new SpecificValidationMetrics(
                meterRegistry, createPrefix(prefix), "size");
        secureValidationMetrics = new SpecificValidationMetrics(
                meterRegistry, createPrefix(prefix), "secure");
    }

    private static Function<MetricName, String> nameCreator(String prefix) {
        return metricName -> "%s.%s".formatted(prefix, metricName);
    }

    private static String createPrefix(String prefix) {
        return prefix + ".validation";
    }

    SpecificValidationMetrics size() {
        return sizeValidationMetrics;
    }

    SpecificValidationMetrics secure() {
        return secureValidationMetrics;
    }
}
