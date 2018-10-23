import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import io.opentracing.tag.Tags
import io.opentracing.util.GlobalTracer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler
import ratpack.exec.Blocking
import ratpack.exec.Execution
import ratpack.exec.Operation
import ratpack.exec.util.ParallelBatch
import ratpack.groovy.test.embed.GroovyEmbeddedApp
import ratpack.http.HttpUrlBuilder
import ratpack.http.client.HttpClient
import ratpack.path.PathBinding

import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RatpackTest extends AgentTestRunner {
  static {
    System.setProperty("dd.integration.ratpack.enabled", "true")
    System.setProperty("dd.integration.netty.enabled", "true")
  }

  OkHttpClient client = OkHttpUtils.client()


  def "test path call"() {
    setup:
    def app = GroovyEmbeddedApp.ratpack {
      handlers {
        get {
          context.render("success")
        }
      }
    }
    def url = app.address.toURL()
    def request = new Request.Builder()
      .url(url)
      .get()
      .build()
    when:
    def resp = client.newCall(request).execute()
    app.close()
    then:
    resp.code() == 200
    resp.body.string() == "success"
    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "netty.request"
          errored false
          spanType DDSpanTypes.HTTP_SERVER
          parent()
          tags {
            "$Tags.COMPONENT.key" "netty"
            "$Tags.HTTP_URL.key" url.toString()
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.HTTP_STATUS.key" 200
            "$Tags.SPAN_KIND.key" "server"
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            "$Tags.PEER_HOSTNAME.key" url.host
            "$Tags.PEER_PORT.key" Integer
            defaultTags()
          }
        }
        span(1) {
          serviceName "unnamed-java-app"
          operationName "ratpack.handler"
          resourceName "GET /"
          errored false
          spanType DDSpanTypes.HTTP_SERVER
          childOf span(0)
          tags {
            "$Tags.COMPONENT.key" "handler"
            "$Tags.HTTP_URL.key" "/"
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.HTTP_STATUS.key" 200
            "$Tags.SPAN_KIND.key" "server"
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            defaultTags()
          }
        }
      }
    }
  }

  def "test path with bindings call"() {
    setup:
    def app = GroovyEmbeddedApp.ratpack {
      handlers {
        prefix(":foo/:bar?") {
          get("baz") { ctx ->
            context.render(ctx.get(PathBinding).description)
          }
        }
      }
    }
    def url = HttpUrl.get(app.address).newBuilder().addPathSegments("a/b/baz").build()
    def request = new Request.Builder()
      .url(url)
      .get()
      .build()
    when:
    def resp = client.newCall(request).execute()
    app.close()
    then:
    resp.code() == 200
    resp.body.string() == ":foo/:bar?/baz"

    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "netty.request"
          errored false
          spanType DDSpanTypes.HTTP_SERVER
          parent()
          tags {
            "$Tags.COMPONENT.key" "netty"
            "$Tags.HTTP_URL.key" url.toString()
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.HTTP_STATUS.key" 200
            "$Tags.SPAN_KIND.key" "server"
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            "$Tags.PEER_HOSTNAME.key" url.host
            "$Tags.PEER_PORT.key" Integer
            defaultTags()
          }
        }
        span(1) {
          serviceName "unnamed-java-app"
          operationName "ratpack.handler"
          resourceName "GET /:foo/:bar?/baz"
          errored false
          spanType DDSpanTypes.HTTP_SERVER
          childOf span(0)
          tags {
            "$Tags.COMPONENT.key" "handler"
            "$Tags.HTTP_URL.key" "/a/b/baz"
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.HTTP_STATUS.key" 200
            "$Tags.SPAN_KIND.key" "server"
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            defaultTags()
          }
        }
      }
    }
  }

  def "test error response"() {
    setup:
    def app = GroovyEmbeddedApp.ratpack {
      handlers {
        get {
          throw new RuntimeException("foo")
        }
      }
    }
    def url = app.address.toURL()
    def request = new Request.Builder()
      .url(url)
      .get()
      .build()
    when:
    def resp = client.newCall(request).execute()
    app.close()
    then:
    resp.code() == 500
    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "netty.request"
          errored true
          spanType DDSpanTypes.HTTP_SERVER
          parent()
          tags {
            "$Tags.COMPONENT.key" "netty"
            "$Tags.HTTP_URL.key" url.toString()
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.HTTP_STATUS.key" 500
            "$Tags.SPAN_KIND.key" "server"
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            "$Tags.PEER_HOSTNAME.key" url.host
            "$Tags.PEER_PORT.key" Integer
            "$Tags.ERROR.key" true
            defaultTags()
          }
        }
        span(1) {
          serviceName "unnamed-java-app"
          operationName "ratpack.handler"
          resourceName "GET /"
          errored true
          spanType DDSpanTypes.HTTP_SERVER
          childOf span(0)
          tags {
            "$Tags.COMPONENT.key" "handler"
            "$Tags.HTTP_URL.key" "/"
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.HTTP_STATUS.key" 500
            "$Tags.SPAN_KIND.key" "server"
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            "$Tags.ERROR.key" true
            defaultTags()
          }
        }
      }
    }
  }

  def "test path call using ratpack http client"() {
    setup:

    Server server = new Server(0)
    server.setHandler(new AbstractHandler() {
      @Override
      void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        response.setContentType("text/plain; charset=utf-8")
        response.setStatus(HttpServletResponse.SC_OK)
        response.getWriter().print(request.pathInfo == "/nested" ? "succ" : "ess")
        baseRequest.setHandled(true)
      }
    })

    server.start()
    def connector = server.getConnectors()[0]
    def address = URI.create("http://localhost:${connector.localPort}")

    def app = GroovyEmbeddedApp.ratpack {
      handlers {
        get { HttpClient httpClient ->
          // 1st internal http client call to nested
          httpClient.get(HttpUrlBuilder.base(address).path("nested").build())
            .map { it.body.text }
            .flatMap { t ->
            // make a 2nd http request and concatenate the two bodies together
            httpClient.get(HttpUrlBuilder.base(address).path("nested2").build()) map { t + it.body.text }
          }
          .then {
            context.render(it)
          }
        }
      }
    }
    def request = new Request.Builder()
      .url(app.address.toURL())
      .get()
      .build()
    when:
    def resp = client.newCall(request).execute()
    app.close()
    server.stop()
    server.join()
    then:
    resp.code() == 200
    resp.body().string() == "success"

    // 3rd is the three traces, ratpack, http client 2 and http client 1
    // 2nd is nested2 from the external server (the result of the 2nd internal http client call)
    // 1st is nested from the external server (the result of the 1st internal http client call)
    assertTraces(1) {
      trace(0, 4) {
        span(0) {
          parent()
          operationName "netty.request"
        }
        span(3) { // why is this the last one?
          childOf span(0)
          operationName "ratpack.handler"
          resourceName "GET /"
          errored false
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT.key" "handler"
            "$Tags.HTTP_URL.key" "/"
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.HTTP_STATUS.key" 200
            "$Tags.SPAN_KIND.key" "server"
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            defaultTags()
          }
        }
        span(1) {
          childOf span(3)
          errored false
          operationName "netty.client.request"
          serviceName "unnamed-java-app"
          tags {
            "$Tags.COMPONENT.key" "netty-client"
            "$Tags.HTTP_URL.key" "${address}/nested2"
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.SPAN_KIND.key"  "client"
            "span.type"  "http"
            "$Tags.HTTP_STATUS.key" 200
            "$Tags.PEER_HOSTNAME.key" String
            "$Tags.PEER_PORT.key" Integer
            defaultTags()
          }
        }
        span(2) {
          childOf span(3)
          operationName "netty.client.request"
          errored false
          serviceName "unnamed-java-app"
          tags {
            "$Tags.COMPONENT.key" "netty-client"
            "$Tags.HTTP_URL.key" "${address}/nested"
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.SPAN_KIND.key"  "client"
            "span.type"  "http"
            "$Tags.HTTP_STATUS.key" 200
            "$Tags.PEER_HOSTNAME.key" String
            "$Tags.PEER_PORT.key" Integer
            defaultTags()
          }
        }
      }
    }
  }

  def "test path call using ratpack streaming http client"() {
    setup:

    Server server = new Server(0)
    server.setHandler(new AbstractHandler() {
      @Override
      void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        response.setContentType("text/plain; charset=utf-8")
        response.setStatus(HttpServletResponse.SC_OK)
        response.getWriter().print("success")
        baseRequest.setHandled(true)
      }
    })

    server.start()
    def connector = server.getConnectors()[0]
    def address = URI.create("http://localhost:${connector.localPort}")

    def app = GroovyEmbeddedApp.ratpack {
      handlers {
        get { HttpClient httpClient ->
          // 1st internal http client call to nested
          httpClient.requestStream(HttpUrlBuilder.base(address).path("nested").build()) {rs -> rs.method("GET")}
            .then {
            it.forwardTo(context.response)
          }
        }
      }
    }
    def request = new Request.Builder()
      .url(app.address.toURL())
      .get()
      .build()
    when:
    def resp = client.newCall(request).execute()
    def body = resp.body().string()
    app.close()
    server.stop()
    server.join()
    then:
    resp.code() == 200
    body == "success"

    // 3rd is the three traces, ratpack, http client 2 and http client 1
    // 2nd is nested2 from the external server (the result of the 2nd internal http client call)
    // 1st is nested from the external server (the result of the 1st internal http client call)
    assertTraces(1) {
      trace(0, 3) {
        span(0) {
          parent()
          operationName "netty.request"
        }
        span(2) { // why is this the last one?
          childOf span(0)
          operationName "ratpack.handler"
          resourceName "GET /"
          errored false
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT.key" "handler"
            "$Tags.HTTP_URL.key" "/"
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.HTTP_STATUS.key" 200
            "$Tags.SPAN_KIND.key" "server"
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            defaultTags()
          }
        }
        span(1) {
          childOf span(2)
          operationName "netty.client.request"
          errored false
          serviceName "unnamed-java-app"
          tags {
            "$Tags.COMPONENT.key" "netty-client"
            "$Tags.HTTP_URL.key" "${address}/nested"
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.SPAN_KIND.key"  "client"
            "span.type"  "http"
            "$Tags.HTTP_STATUS.key" 200
            "$Tags.PEER_HOSTNAME.key" String
            "$Tags.PEER_PORT.key" Integer
            defaultTags()
          }
        }
      }
    }
  }

  def "test parallel forked executions"() {
    setup:

    Server server = new Server(0)
    server.setHandler(new AbstractHandler() {
      @Override
      void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        response.setContentType("text/plain; charset=utf-8")
        response.setStatus(HttpServletResponse.SC_OK)
        response.getWriter().print(request.pathInfo == "/nested" ? "succ" : "ess")
        baseRequest.setHandled(true)
      }
    })

    server.start()
    def connector = server.getConnectors()[0]
    def address = URI.create("http://localhost:${connector.localPort}")

    def app = GroovyEmbeddedApp.ratpack {
      handlers {
        get { HttpClient httpClient ->
          // 1st internal http client call to nested
          ParallelBatch.of(
            httpClient.get(HttpUrlBuilder.base(address).path("nested").build()).map { it.body.text },
            httpClient.get(HttpUrlBuilder.base(address).path("nested2").build()).map { it.body.text }
          ).yield() map {
            it.join('')
          } then {
            context.render(it)
          }
        }
      }
    }
    def request = new Request.Builder()
      .url(app.address.toURL())
      .get()
      .build()
    when:
    def resp = client.newCall(request).execute()
    app.close()
    server.stop()
    server.join()
    then:
    resp.code() == 200
    resp.body().string() == "success"

    // 3rd is the three traces, ratpack, http client 2 and http client 1
    // 2nd is nested2 from the external server (the result of the 2nd internal http client call)
    // 1st is nested from the external server (the result of the 1st internal http client call)
    assertTraces(1) {
      trace(0, 6) {
        span(1) {
          parent()
          operationName "netty.request"
        }
        span(0) { // why is this the first one?
          childOf span(1)
          operationName "ratpack.handler"
        }
        span(3) {
          childOf span(0)
          operationName "ratpack.execution"
        }
        span(5) {
          childOf span(0)
          operationName "ratpack.execution"
        }
        span(2) {
          childOf span(3)
          operationName "netty.client.request"
        }
        span(4) {
          childOf span(5)
          operationName "netty.client.request"
        }
      }
    }

  }


  def "test parallel blocking forked executions"() {
    setup:


    def app = GroovyEmbeddedApp.ratpack {
      handlers {
        get { HttpClient httpClient ->
          ParallelBatch.of(
            Blocking.get {
              GlobalTracer.get().activeSpan().setTag("foo", "123")
              "123"
            },
            Blocking.get {
              GlobalTracer.get().activeSpan().setTag("foo", "abc")
              ["a","b","c"]
            }.flatMap { ess ->
              def promises = ess.collect { c ->
                Blocking.get {
                  GlobalTracer.get().activeSpan().setTag("foo", c)
                  c
                }
              }
              ParallelBatch.of(promises)
                .yield()
                .map {s ->
                s.join('')
              }
            }
          ).yield() map {
            it.join('')
          } then {
            context.render(it)
          }
        }
      }
    }
    def request = new Request.Builder()
      .url(app.address.toURL())
      .get()
      .build()
    when:
    def resp = client.newCall(request).execute()
    app.close()

    then:
    resp.code() == 200
    resp.body().string() == "123abc"

    // 3rd is the three traces, ratpack, http client 2 and http client 1
    // 2nd is nested2 from the external server (the result of the 2nd internal http client call)
    // 1st is nested from the external server (the result of the 1st internal http client call)
    assertTraces(1) {
      trace(0, 7) {
        span(2) { // why is this the third one?
          parent()
          operationName "netty.request"
        }
        span(1) { // why is this the second one?
          childOf span(2)
          operationName "ratpack.handler"
        }
        span(0) {
          childOf span(5)
          operationName "ratpack.execution"
          tags {
            "foo" "c"
            "$Tags.COMPONENT.key" "execution"
            "$Tags.SPAN_KIND.key" "server"
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            defaultTags()
          }
        }
        span(3) {
          childOf span(5)
          operationName "ratpack.execution"
          tags {
            "foo" "b"
            "$Tags.COMPONENT.key" "execution"
            "$Tags.SPAN_KIND.key" "server"
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            defaultTags()
          }
        }
        span(4) {
          childOf span(5)
          operationName "ratpack.execution"
          tags {
            "foo" "a"
            "$Tags.COMPONENT.key" "execution"
            "$Tags.SPAN_KIND.key" "server"
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            defaultTags()
          }
        }
        span(5) {
          childOf span(1)
          operationName "ratpack.execution"
          tags {
            "foo" "abc"
            "$Tags.COMPONENT.key" "execution"
            "$Tags.SPAN_KIND.key" "server"
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            defaultTags()
          }
        }
        span(6) {
          childOf span(1)
          operationName "ratpack.execution"
          tags {
            "foo" "123"
            "$Tags.COMPONENT.key" "execution"
            "$Tags.SPAN_KIND.key" "server"
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            defaultTags()
          }
        }
      }
    }
  }

  def "execution lasts longer than a single request"() {
    setup:
    CountDownLatch latch = new CountDownLatch(1)
    def app = GroovyEmbeddedApp.ratpack {
      handlers {
        get('start') {
          Execution.fork().start(Blocking.op {latch.await(10, TimeUnit.SECONDS)})

          context.render("started")
        }
        get('finish') {
          latch.countDown()
          context.render("finished")
        }
      }
    }
    def baseUrl = HttpUrl.get(app.address)
    def startUrl = baseUrl.newBuilder().addPathSegments("start").build()
    def finishUrl = baseUrl.newBuilder().addPathSegments("finish").build()
    def startRequest = new Request.Builder()
      .url(startUrl)
      .get()
      .build()
    def finishRequest = new Request.Builder()
      .url(finishUrl)
      .get()
      .build()
    when:
    def startResp = client.newCall(startRequest).execute()
    def finishResp = client.newCall(finishRequest).execute()
    app.close()
    then:
    startResp.body().string() == "started"
    finishResp.body().string() == "finished"
    latch.count == 0
    assertTraces(2) {
      trace(0, 3) {
        span(0) {
          childOf span(2)
          operationName "ratpack.execution"
        }
        span(1) { // why is this the third one?
          parent()
          operationName "netty.request"
        }
        span(2) { // why is this the second one?
          childOf span(1)
          operationName "ratpack.handler"
          resourceName "GET /start"
        }
      }
      trace(1, 2) {
        span(0) {
          parent()
          operationName "netty.request"
        }
        span(1) {
          childOf span(0)
          operationName "ratpack.handler"
          resourceName "GET /finish"
        }
      }
    }
  }

  def "forked execution errors are reported"() {
    setup:
    def app = GroovyEmbeddedApp.ratpack {
      handlers {
        get {
          CountDownLatch latch = new CountDownLatch(1)
          Execution.fork().onComplete { latch.countDown() }
            .start(Operation.of {throw new RuntimeException("foo") })
          Blocking.get {
            latch.await()
          } then  {
            context.render("ok")
          }
        }
      }
    }
    def url = app.address.toURL()
    def request = new Request.Builder()
      .url(url)
      .get()
      .build()
    when:
    def resp = client.newCall(request).execute()
    app.close()
    then:
    resp.code() == 200
    resp.body().string() == "ok"
    assertTraces(1) {
      trace(0, 3) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "netty.request"
          errored false
          spanType DDSpanTypes.HTTP_SERVER
          parent()
        }
        span(1) {
          serviceName "unnamed-java-app"
          operationName "ratpack.handler"
          resourceName "GET /"
          errored false
          spanType DDSpanTypes.HTTP_SERVER
          childOf span(0)
        }
        span(2) {
          childOf span(1)
          operationName "ratpack.execution"
          errored true
          tags {
            "$Tags.COMPONENT.key" "execution"
            "$Tags.SPAN_KIND.key" "server"
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            errorTags(RuntimeException, "foo")
            defaultTags()
          }
        }
      }
    }
  }
}
