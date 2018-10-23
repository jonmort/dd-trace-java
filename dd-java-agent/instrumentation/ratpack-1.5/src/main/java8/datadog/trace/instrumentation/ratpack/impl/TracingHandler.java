package datadog.trace.instrumentation.ratpack.impl;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.context.TraceScope;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import ratpack.exec.Execution;
import ratpack.handling.Context;
import ratpack.handling.Handler;
import ratpack.http.Request;
import ratpack.http.Status;
import ratpack.registry.Registry;
import ratpack.registry.RegistryBuilder;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * This Ratpack handler reads tracing headers from the incoming request, starts a scope and ensures
 * that the scope is closed when the response is sent
 */
public final class TracingHandler implements Handler {
  @Override
  public void handle(final Context ctx) {
    final Request request = ctx.getRequest();

    final Span parent = GlobalTracer.get().activeSpan();
    final Scope scope =
      GlobalTracer.get()
        .buildSpan("ratpack.handler")
        .asChildOf(parent)
        .withTag(Tags.COMPONENT.getKey(), "handler")
        .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
        .withTag(DDTags.SPAN_TYPE, DDSpanTypes.HTTP_SERVER)
        .withTag(Tags.HTTP_METHOD.getKey(), request.getMethod().getName())
        .withTag(Tags.HTTP_URL.getKey(), request.getUri())
        .startActive(true);

    Execution.current()
      .add(Scope.class, scope);

    if (scope instanceof TraceScope) {
      TraceScope traceScope = (TraceScope) scope;
      traceScope.setAsyncPropagation(true);
      Execution.current()
        .add(TraceScope.Continuation.class, traceScope.capture());
    }

    ctx.getResponse()
      .beforeSend(
        response -> {
          // any continuations left in the execution registry at this point should be closed.
          Execution.current().maybeGet(TraceScope.Continuation.class)
            .ifPresent(TraceScope.Continuation::close);
          Span span = scope.span();
          span.setTag(DDTags.RESOURCE_NAME, getResourceName(ctx));
          final Status status = response.getStatus();
          if (status != null) {
            if (status.is5xx()) {
              Tags.ERROR.set(span, true);
            }
            Tags.HTTP_STATUS.set(span, status.getCode());
          }
          scope.close();
        });

    ctx.next();
  }

  private static String getResourceName(final Context ctx) {
    String description = ctx.getPathBinding().getDescription();
    if (description == null || description.isEmpty()) {
      description = ctx.getRequest().getUri();
    }
    if (!description.startsWith("/")) {
      description = "/" + description;
    }
    return ctx.getRequest().getMethod().getName() + " " + description;
  }
}
