package datadog.trace.instrumentation.ratpack.impl;

import datadog.trace.context.TraceScope;
import io.netty.channel.EventLoop;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.log.Fields;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import ratpack.exec.ExecStarter;
import ratpack.exec.Execution;
import ratpack.func.Action;
import ratpack.registry.RegistrySpec;

import java.util.Collections;

/**
 * An ExecStarter that ensures that all forked executions have a span.
 */
public class TracingExecStarter implements ExecStarter {

  private final ExecStarter delegate;
  private final Span span;
  private Scope scope;
  private Action<? super Throwable> onErrorAction = this::scopeErrorHandler;
  private Action<? super Execution> onCompleteAction = this::closeScope;
  private Action<? super Execution> onStartAction = this::startSpan;
  private TraceScope.Continuation continuation;
  private TraceScope traceScope;

  @SuppressWarnings("WeakerAccess")
  public TracingExecStarter(ExecStarter delegate) {
    this.delegate = delegate;
    Span parentSpan = Execution.current()
      .maybeGet(Scope.class)
      .map(Scope::span)
      .orElse(null);

    this.span = GlobalTracer.get()
      .buildSpan("ratpack.execution")
      .asChildOf(parentSpan)
      .withTag(Tags.COMPONENT.getKey(), "execution")
      .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
      .ignoreActiveSpan()
      .start();
  }

  @Override
  public void start(Action<? super Execution> initialExecutionSegment) {
    // this will be run on the thread/execution that is doing the forking. We create the span here so that it will
    // be associated with the parent scope
    scope = GlobalTracer.get().scopeManager().activate(span, true);
    if (scope instanceof TraceScope) {
      traceScope = (TraceScope) scope;
      traceScope.setAsyncPropagation(true);
      continuation = traceScope.capture();
    }
    // this needs to pass on all our things and the called ones too
    delegate.onError(onErrorAction);
    delegate.onComplete(onCompleteAction);
    delegate.onStart(onStartAction);
    delegate.start(initialExecutionSegment);
  }


  @Override
  public ExecStarter onError(Action<? super Throwable> onError) {
    onErrorAction = onError.prepend(this::scopeErrorHandler);
    return this;
  }

  @Override
  public ExecStarter onComplete(Action<? super Execution> onComplete) {
    onCompleteAction = onComplete.prepend(this::closeScope);
    return this;
  }

  @Override
  public ExecStarter onStart(Action<? super Execution> onStart) {
    onStartAction = onStart.prepend(this::startSpan);
    return this;
  }

  @Override
  public ExecStarter register(Action<? super RegistrySpec> action) {
    delegate.register(action);
    return this;
  }

  @Override
  public ExecStarter eventLoop(EventLoop eventLoop) {
    delegate.eventLoop(eventLoop);
    return this;
  }

  private void startSpan(Execution execution) {
    traceScope = continuation.activate();
    execution.add(Scope.class, scope);
    execution.add(TraceScope.Continuation.class, continuation);
  }

  private void scopeErrorHandler(Throwable t) {
    Tags.ERROR.set(scope.span(), true);
    scope.span().log(Collections.singletonMap(Fields.ERROR_OBJECT, t));
  }

  private void closeScope(Execution e) {
    if(traceScope != null) {
      traceScope.close();
    }
  }
}
