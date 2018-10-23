package datadog.trace.instrumentation.ratpack.impl;

import datadog.trace.context.TraceScope;
import ratpack.exec.ExecInterceptor;
import ratpack.exec.Execution;
import ratpack.func.Block;

import java.util.Optional;

/**
 * Wrap any execution segments in a trace continuation
 */
public class RatpackTraceInterceptor implements ExecInterceptor {
  @Override
  public void intercept(Execution execution, ExecType execType, Block executionSegment) throws Exception {
    Optional<TraceScope> originalTraceScope = execution.maybeGet(TraceScope.class);
    Optional<TraceScope.Continuation> continuation = execution.maybeGet(TraceScope.Continuation.class);
    // activate our continuation
    Optional<TraceScope> activatedTraceScope = continuation.map(TraceScope.Continuation::activate);
    Optional<UsageTrackingContinuation> nextContinuation = activatedTraceScope.map(traceScope -> {
      traceScope.setAsyncPropagation(true);
      return traceScope.capture();
    })
      .map(UsageTrackingContinuation::new);
    // once activated, remove it from the execution so it can't be used again
    nextContinuation.ifPresent(c -> {
      execution.remove(TraceScope.Continuation.class);
      execution.add(TraceScope.Continuation.class, c);
    });
    // remove the original trace scope and replace it with the new one from activating the continuation
    activatedTraceScope.ifPresent(s -> {
      execution.remove(TraceScope.class);
      execution.add(TraceScope.class, s);
    });

    try {
      executionSegment.execute();
    } finally {
      // restore original traceScope and remove the continuation
      originalTraceScope.ifPresent(s -> {
        execution.remove(TraceScope.class);
        execution.add(TraceScope.class, s);
      });

      // complete our continuation
      activatedTraceScope.ifPresent(TraceScope::close);

      // if the continuation we added is still the continuation in the execution then remove it
      nextContinuation.filter(c ->
        Execution.current()
          .maybeGet(TraceScope.Continuation.class)
          .filter(x -> x == c)
          .isPresent())
        .ifPresent(c -> Execution.current().remove(TraceScope.Continuation.class));

      // close the continuation if it hasn't been used
      nextContinuation
        .filter(c -> !c.isUsed())
        .ifPresent(UsageTrackingContinuation::close);
    }
  }

  /**
   * Simple class to track usage of continuations to prevent closing of already activated Continuations.
   * This is just used to prevent unnecessary log messages
   */
  public static class UsageTrackingContinuation implements TraceScope.Continuation {
    private final TraceScope.Continuation delegate;
    private boolean used = false;

    UsageTrackingContinuation(TraceScope.Continuation delegate) {
      this.delegate = delegate;
    }

    @Override
    public TraceScope activate() {
      return delegate.activate();
    }

    @Override
    public void close() {
      delegate.close();
    }

    boolean isUsed() {
      return used;
    }
  }

}
