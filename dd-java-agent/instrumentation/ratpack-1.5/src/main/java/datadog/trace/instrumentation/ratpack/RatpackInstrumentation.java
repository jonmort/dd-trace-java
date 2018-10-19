package datadog.trace.instrumentation.ratpack;

import static datadog.trace.agent.tooling.ByteBuddyElementMatchers.safeHasSuperType;
import static datadog.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClassWithMethod;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.ratpack.impl.RatpackServerAdvice;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
@Slf4j
public final class RatpackInstrumentation extends Instrumenter.Default {

  private static final String EXEC_NAME = "ratpack";

  private static final ElementMatcher.Junction.AbstractBase<ClassLoader>
      CLASSLOADER_CONTAINS_RATPACK_1_5_OR_ABOVE =
          classLoaderHasClassWithMethod("ratpack.server.ServerConfig", "getConnectQueueSize");

  public RatpackInstrumentation() {
    super(EXEC_NAME);
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("ratpack.server.internal.ServerRegistry");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return CLASSLOADER_CONTAINS_RATPACK_1_5_OR_ABOVE;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      // service registry helpers
      "datadog.trace.instrumentation.ratpack.impl.RatpackServerAdvice",
      "datadog.trace.instrumentation.ratpack.impl.RatpackServerAdvice$RatpackServerRegistryAdvice",
      "datadog.trace.instrumentation.ratpack.impl.RatpackTraceInterceptor",
      "datadog.trace.instrumentation.ratpack.impl.TracingHandler"
    };
  }

  @Override
  public Map<ElementMatcher, String> transformers() {
    final Map<ElementMatcher, String> transformers = new HashMap<>();
    transformers.put(
        isMethod().and(isStatic()).and(named("serverRegistry")),
        RatpackServerAdvice.RatpackServerRegistryAdvice.class.getName());
    return transformers;
  }

  @AutoService(Instrumenter.class)
  public static class ExecutionInstrumentation extends Default {

    public ExecutionInstrumentation() {
      super(EXEC_NAME);
    }

    @Override
    protected boolean defaultEnabled() {
      return false;
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named("ratpack.exec.Execution")
          .or(not(isInterface()).and(safeHasSuperType(named("ratpack.exec.Execution"))));
    }

    @Override
    public ElementMatcher<ClassLoader> classLoaderMatcher() {
      return CLASSLOADER_CONTAINS_RATPACK_1_5_OR_ABOVE;
    }

    @Override
    public String[] helperClassNames() {
      return new String[] {
        // exec helpers
        "datadog.trace.instrumentation.ratpack.impl.RatpackServerAdvice$ExecutionAdvice",
        "datadog.trace.instrumentation.ratpack.impl.TracingExecStarter"
      };
    }

    @Override
    public Map<ElementMatcher, String> transformers() {
      final Map<ElementMatcher, String> transformers = new HashMap<>();
      transformers.put(
          named("fork").and(returns(named("ratpack.exec.ExecStarter"))),
          RatpackServerAdvice.ExecutionAdvice.class.getName());
      return transformers;
    }
  }
}
