package datadog.trace.instrumentation.ratpack.impl;

import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import ratpack.exec.ExecInterceptor;
import ratpack.exec.ExecStarter;
import ratpack.func.Function;
import ratpack.handling.HandlerDecorator;
import ratpack.registry.Registry;

@Slf4j
public class RatpackServerAdvice {
  /**
   * Add the Execution trace interceptor and the request tracing handler to the base ratpack registry as a user
   * injected registry
   */
  public static class RatpackServerRegistryAdvice {
    @Advice.OnMethodEnter
    public static void injectTracing(
      @Advice.Argument(value = 4, readOnly = false)
        Function<? super Registry, ? extends Registry> userRegistryFactory) {
      // add a handler decorator to ensure that the correct continuation is used everywhere....
      Registry traceRegistry = Registry.builder()
        .add(ExecInterceptor.class, new RatpackTraceInterceptor())
        .add(HandlerDecorator.prepend(new TracingHandler()))
        .build();

      try {
        //noinspection UnusedAssignment
        userRegistryFactory = userRegistryFactory.andThen(traceRegistry::join);
      } catch (Exception e) {
        log.error("Failed to add instrumentation to ratpack registry", e);
      }
    }
  }

  /**
   * Wrap any ExecStarters with a TracingExecStarter to instrument all Ratapck Executions
   */
  public static class ExecutionAdvice {
    @Advice.OnMethodExit
    public static void addTracingExecStarter(
      @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) ExecStarter starter) {
      //noinspection UnusedAssignment
      starter = new TracingExecStarter(starter);
    }
  }
}
