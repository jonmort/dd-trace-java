package datadog.trace.agent.tooling;

import java.io.File;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilder.LocationStrategy;
import net.bytebuddy.dynamic.ClassFileLocator;

/** A bytebuddy advice builder with default DataDog settings. */
@Slf4j
public class DDAdvice extends AgentBuilder.Transformer.ForAdvice {
  private static final LocationStrategy AGENT_CLASS_LOCATION_STRATEGY;

  static {
    ClassFileLocator agentClassLocator =
        ClassFileLocator.ForClassLoader.of(Utils.getAgentClassLoader());
    ClassFileLocator bootStrapLocator = null;
    // FIXME: ugly logic
    String bsJarLoc = null;
    try {
      Class<?> tracingAgentClass = ClassLoader.getSystemClassLoader().loadClass("datadog.trace.agent.TracingAgent");
      bsJarLoc = (String) tracingAgentClass.getDeclaredField("BS_JAR").get(null);
      File agentBSJar = new File(bsJarLoc);
      bootStrapLocator = ClassFileLocator.ForJarFile.of(agentBSJar);
    } catch (Exception e) {
      log.error("Failed to initialize bootstrap resource locator", e);
    }
    if (null == bootStrapLocator) {
      AGENT_CLASS_LOCATION_STRATEGY = new AgentBuilder.LocationStrategy.Simple(agentClassLocator);
    } else {
      AGENT_CLASS_LOCATION_STRATEGY =
        new LocationStrategy.Compound(
          new LocationStrategy.Simple(bootStrapLocator),
          new LocationStrategy.Simple(agentClassLocator));
    }
  }

  /**
   * Create bytebuddy advice with default datadog settings.
   *
   * @return the bytebuddy advice
   */
  public static AgentBuilder.Transformer.ForAdvice create() {
    return new DDAdvice()
        .with(AGENT_CLASS_LOCATION_STRATEGY)
        .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler());
  }

  private DDAdvice() {}
}
