package datadog.trace.agent;

import java.net.URL;
import java.net.URLClassLoader;

public class DatadogClassLoader extends URLClassLoader {
  public DatadogClassLoader(URL[] urls, ClassLoader classLoader) {
    super(urls, classLoader);
  }
}
