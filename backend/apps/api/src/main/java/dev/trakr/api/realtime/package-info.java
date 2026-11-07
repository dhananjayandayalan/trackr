// BLOCK 1: module declaration — Server-Sent Events to browsers, fed from Kafka (not from in-process
// events: every api instance must push what any instance wrote). project: who may open a project's
// stream; identity: the signed-in user; shared: errors.
@ApplicationModule(displayName = "Realtime", allowedDependencies = { "project", "identity", "shared" })
package dev.trakr.api.realtime;

import org.springframework.modulith.ApplicationModule;
