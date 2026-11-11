package dev.trakr.api.realtime.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * trakr.sse.subscribers: how many SSE streams this instance holds open. A gauge is read when it is scraped
 * (or pushed), not updated on every subscribe: Micrometer calls {@code subscriberCount} then, so the hub
 * needs no metrics code of its own. Boot registers every MeterBinder bean with the registry at startup.
 */
@Component
class RealtimeMetrics implements MeterBinder {

    private final SseHub hub;

    RealtimeMetrics(SseHub hub) {
        this.hub = hub;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("trakr.sse.subscribers", hub, SseHub::subscriberCount)
                .description("Open SSE streams (board and /me) on this instance")
                .register(registry);
    }
}
