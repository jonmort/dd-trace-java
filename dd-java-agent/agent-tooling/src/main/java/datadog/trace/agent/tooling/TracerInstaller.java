package datadog.trace.agent.tooling;

import io.opentracing.Tracer;
import io.opentracing.contrib.tracerresolver.TracerResolver;
import io.opentracing.util.GlobalTracer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TracerInstaller {
  /** Register a global tracer if no global tracer is already registered. */
  public static synchronized void installGlobalTracer() {
    if (!GlobalTracer.isRegistered()) {
      // Try to obtain a tracer using the TracerResolver
      final Tracer resolved = TracerResolver.resolveTracer();
      if (resolved != null) {
        try {
          GlobalTracer.register(resolved);
        } catch (final RuntimeException re) {
          log.warn("Failed to register tracer '" + resolved + "'", re);
        }
      } else {
        log.warn("Failed to resolve dd tracer");
      }
    }
  }
}
