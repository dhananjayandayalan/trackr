package dev.trakr.collab.room;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/** trakr.collab.rooms: issues being co-edited on this instance, read from the registry when metrics are taken. */
@Component
class CollabMetrics implements MeterBinder {

    private final RoomRegistry rooms;

    CollabMetrics(RoomRegistry rooms) {
        this.rooms = rooms;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("trakr.collab.rooms", rooms, RoomRegistry::size)
                .description("Collaborative editing rooms open on this instance")
                .register(registry);
    }
}
