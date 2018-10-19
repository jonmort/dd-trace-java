package datadog.trace.instrumentation.ratpack.impl;

import datadog.trace.context.TraceScope;
import io.netty.channel.EventLoop;
import io.opentracing.Scope;
//import io.opentracing.Span;
import io.opentracing.Span;
import io.opentracing.log.Fields;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import ratpack.exec.ExecStarter;
import ratpack.exec.Execution;
import ratpack.func.Action;
import ratpack.registry.RegistrySpec;

import java.util.Collections;

import static ratpack.func.Action.noopIfNull;

public class TracingExecStarter implements ExecStarter {

  private final ExecStarter delegate;
  private final Span span;
  private Scope scope;
  private Action<? super Throwable> onErrorAction;
  private Action<? super Execution> onCompleteAction;
  private Action<? super Execution> onStartAction;
  private Action<? super RegistrySpec> onRegisterAction;
  private TraceScope.Continuation continuation;
  private TraceScope traceScope;

  public TracingExecStarter(ExecStarter delegate) {
    System.out.println("TracingExecStarter Constructor");
    this.delegate = delegate;
    Span parent = GlobalTracer.get().activeSpan();
    this.span = GlobalTracer.get()
      .buildSpan("ratpack.execution")
      .asChildOf(parent)
      .withTag(Tags.COMPONENT.getKey(), "execution")
      .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
      .start();
  }

  @Override
  public void start(Action<? super Execution> initialExecutionSegment) {

    if (scope instanceof TraceScope) {
      TraceScope traceScope = (TraceScope) scope;
      traceScope.setAsyncPropagation(true);
      continuation = traceScope.capture();
    }
//    // this needs to pass on all our things and the called ones too
    delegate.onError(noopIfNull(onErrorAction).prepend(this::scopeErrorHandler));
    delegate.onComplete(noopIfNull(onCompleteAction).prepend(this::closeScope));
    delegate.onStart(noopIfNull(onStartAction).prepend(this::startSpan));
    delegate.register(noopIfNull(onRegisterAction).prepend(this::addToRegistry));
    delegate.start(initialExecutionSegment);
  }


  @Override
  public ExecStarter onError(Action<? super Throwable> onError) {
    onErrorAction = onError;
    return this;
  }

  @Override
  public ExecStarter onComplete(Action<? super Execution> onComplete) {
    onCompleteAction = onComplete;
    return this;
  }

  @Override
  public ExecStarter onStart(Action<? super Execution> onStart) {
    onStartAction = onStart;
    return this;
  }

  @Override
  public ExecStarter register(Action<? super RegistrySpec> action) {
    onRegisterAction = action;
    return this;
  }

  @Override
  public ExecStarter eventLoop(EventLoop eventLoop) {
    delegate.eventLoop(eventLoop);
    return this;
  }

  private void startSpan(Execution execution) {
    scope = GlobalTracer.get().scopeManager().activate(span, false);
    execution.add(Scope.class, scope)
      .add(TraceScope.Continuation.class, continuation);
    traceScope = execution.maybeGet(TraceScope.Continuation.class).map(TraceScope.Continuation::activate).orElse(null);
  }

  private void scopeErrorHandler(Throwable t) {
    Tags.ERROR.set(scope.span(), true);
    scope.span().log(Collections.singletonMap(Fields.ERROR_OBJECT, t));
  }

  private void closeScope(Execution e) {
    if(traceScope != null) {
      traceScope.close();
    }
    scope.span().finish();
    scope.close();
  }

  private void addToRegistry(RegistrySpec rs) {

  }
}
