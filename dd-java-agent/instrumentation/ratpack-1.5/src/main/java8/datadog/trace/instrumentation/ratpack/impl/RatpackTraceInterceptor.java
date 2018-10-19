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
    Optional<TraceScope> activatedScope = execution.maybeGet(TraceScope.Continuation.class)
      .map(TraceScope.Continuation::activate);
    try {
      executionSegment.execute();
    } finally {
      activatedScope.ifPresent(TraceScope::close);
    }
  }

}
