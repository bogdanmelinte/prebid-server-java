package org.prebid.server.metric;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * AdapterType metrics support.
 */
class AdapterTypeMetrics extends UpdatableMetrics {

    private final Function<MetricName, RequestTypeMetrics> requestTypeMetricsCreator;
    private final Map<MetricName, RequestTypeMetrics> requestTypeMetrics;
    private final RequestMetrics requestMetrics;
    private final Function<String, BidTypeMetrics> bidTypeMetricsCreator;
    private final Map<String, BidTypeMetrics> bidTypeMetrics;
    private final ResponseMetrics responseMetrics;

    AdapterTypeMetrics(MeterRegistry meterRegistry, String adapterType) {
        super(Objects.requireNonNull(meterRegistry),
                nameCreator(createAdapterPrefix(Objects.requireNonNull(adapterType))));

        bidTypeMetricsCreator = bidType ->
                new BidTypeMetrics(meterRegistry, createAdapterPrefix(adapterType), bidType);
        requestTypeMetricsCreator = requestType ->
                new RequestTypeMetrics(meterRegistry, createAdapterPrefix(adapterType), requestType);
        requestTypeMetrics = new HashMap<>();
        requestMetrics = new RequestMetrics(meterRegistry, createAdapterPrefix(adapterType));
        bidTypeMetrics = new HashMap<>();
        responseMetrics = new ResponseMetrics(meterRegistry, createAdapterPrefix(adapterType));
    }

    AdapterTypeMetrics(MeterRegistry meterRegistry,
                       String accountAdapterPrefix,
                       String adapterType) {
        super(Objects.requireNonNull(meterRegistry),
                nameCreator(createAdapterPrefix(Objects.requireNonNull(accountAdapterPrefix),
                        Objects.requireNonNull(adapterType))));

        requestMetrics = new RequestMetrics(meterRegistry,
                createAdapterPrefix(accountAdapterPrefix, adapterType));

        // not used for account.adapter.adapters metrics
        requestTypeMetricsCreator = null;
        requestTypeMetrics = null;
        bidTypeMetricsCreator = null;
        bidTypeMetrics = null;
        responseMetrics = null;
    }

    private static String createAdapterPrefix(String adapterType) {
        return "adapter." + adapterType;
    }

    private static String createAdapterPrefix(String adapterPrefix, String adapterType) {
        return "%s.%s".formatted(adapterPrefix, adapterType);
    }

    private static Function<MetricName, String> nameCreator(String prefix) {
        return metricName -> "%s.%s".formatted(prefix, metricName);
    }

    RequestTypeMetrics requestType(MetricName requestType) {
        return requestTypeMetrics.computeIfAbsent(requestType, requestTypeMetricsCreator);
    }

    RequestMetrics request() {
        return requestMetrics;
    }

    BidTypeMetrics forBidType(String bidType) {
        return bidTypeMetrics.computeIfAbsent(bidType, bidTypeMetricsCreator);
    }

    ResponseMetrics response() {
        return responseMetrics;
    }
}
