package com.example.reproducer;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.oauth2.OAuth2Auth;
import io.vertx.ext.auth.oauth2.OAuth2FlowType;
import io.vertx.ext.auth.oauth2.OAuth2Options;
import io.vertx.ext.auth.oauth2.Oauth2Credentials;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.*;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.ServerSocket;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(WireMockExtension.class)
@ExtendWith(VertxExtension.class)
class Reproducer
{
  protected HttpServer server;
  protected HttpClient client;
  protected WebClient webClient;
  protected Router router;
  protected int webServerPort;

  @BeforeEach
  void setUp(Vertx vertx, VertxTestContext testContext, WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception
  {
    webServerPort = getRandomPort();
    router = Router.router(vertx);
    server = vertx.createHttpServer(new HttpServerOptions().setPort(webServerPort).setHost("localhost"));
    client = vertx.createHttpClient(new HttpClientOptions().setDefaultPort(webServerPort).setDefaultHost("localhost"));
    webClient = createOAuth2WebClient(vertx, wireMockRuntimeInfo);

    server.requestHandler(router)
      .listen()
      .onComplete(ar -> {
        if (ar.succeeded())
          System.out.println("HTTP Server started " + ar.result().actualPort());
        else
          System.out.println("Failed to start HTTP server");
        testContext.completeNow();
      });
  }

  @AfterEach
  void shutDown(VertxTestContext testContext)
  {
    webClient.close();
    CompositeFuture.all(client.close(), server.close())
      .onComplete(v -> testContext.completeNow());
  }


  @Test
  void test(Vertx vertx, VertxTestContext testContext, WireMockRuntimeInfo wireMockRuntimeInfo)
  {
    stubToken();
    stubTest();

    router.get("/")
      .handler(rc -> {

        rc.data().put("context", Vertx.currentContext().toString());

        //Future.succeededFuture()
        getData(wireMockRuntimeInfo)
          .onComplete(arr -> getData(wireMockRuntimeInfo)
            .onComplete(ar -> {
              var initialContext = rc.get("context");
              var currentContext = Vertx.currentContext().toString();

              System.out.println("initialContext: " + initialContext);
              System.out.println("currentContext: " + currentContext);

              if (!initialContext.equals(currentContext))
                rc.response().setStatusCode(500).setStatusMessage(initialContext + " != " + currentContext).send();
              else
                rc.response().setStatusCode(200).send();
            }));
      });

    client.request(HttpMethod.GET, "/")
      .onSuccess(req -> {
        req.response()
          .onSuccess(res -> testContext.verify(() -> {
            assertThat(res).extracting(
              HttpClientResponse::statusCode,
              HttpClientResponse::statusMessage
            ).contains(
              200,
              "OK"
            );

            testContext.completeNow();
          }))
          .onFailure(testContext::failNow);
        req.end();
      })
      .onFailure(testContext::failNow);
  }

  Future<JsonArray> getData(WireMockRuntimeInfo wireMockRuntimeInfo)
  {
    Promise<JsonArray> promise = Promise.promise();
    webClient.get(wireMockRuntimeInfo.getHttpBaseUrl() + "/test")
      .addQueryParam("param", "value")
      .putHeader(HttpHeaderNames.ACCEPT.toString(), "application/json")
      .send()
      .map(HttpResponse::body)
      .onSuccess(event -> {
        try
        {
          promise.complete(new JsonArray(event));
        }
        catch (Exception e)
        {
          promise.fail(e);
        }
      })
      .onFailure(promise::fail);

    return promise.future();
  }

  WebClient createOAuth2WebClient(Vertx vertx, WireMockRuntimeInfo wireMockRuntimeInfo)
  {
    var oAuthClientOptionOptions = new OAuth2WebClientOptions()
      .setLeeway(0)
      .setRenewTokenOnForbidden(true);

    var oAuthOptions = new OAuth2Options()
      .setFlow(OAuth2FlowType.CLIENT)
      .setSite(wireMockRuntimeInfo.getHttpBaseUrl())
      .setTokenPath(wireMockRuntimeInfo.getHttpBaseUrl() + "/token")
      .setClientId("clientId")
      .setClientSecret("secret");

    var webClientOptions = new WebClientOptions(new JsonObject());

    return OAuth2WebClient.create(
      WebClient.create(vertx, webClientOptions),
      OAuth2Auth.create(vertx, oAuthOptions), oAuthClientOptionOptions)
      .withCredentials(new Oauth2Credentials());
  }


  private void stubToken()
  {
    stubFor(post("/token")
      .willReturn(ok()
        .withHeader("Content-Type", "application/json;charset=UTF-8")
        .withBody("{\"access_token\":\"token\",\"token_type\":\"bearer\",\"expires_in\":1}")
      )
    );
  }

  private void stubTest()
  {
    stubFor(get("/test")
      .willReturn(ok()
        .withHeader("Content-Type", "application/json;charset=UTF-8")
        .withBody("[]")
      )
    );
  }

  private int getRandomPort()
  {
    try (ServerSocket socket = new ServerSocket(0))
    {
      return socket.getLocalPort();
    }
    catch (IOException e)
    {
      return 54321;
    }
  }
}



