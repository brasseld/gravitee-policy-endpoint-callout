/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.endpointcallout;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.gravitee.apim.gateway.tests.sdk.AbstractPolicyTest;
import io.gravitee.apim.gateway.tests.sdk.annotations.DeployApi;
import io.gravitee.apim.gateway.tests.sdk.annotations.GatewayTest;
import io.gravitee.apim.gateway.tests.sdk.policy.PolicyBuilder;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.definition.model.Api;
import io.gravitee.plugin.policy.PolicyPlugin;
import io.gravitee.policy.endpointcallout.configuration.EndpointCalloutPolicyConfiguration;
import io.reactivex.observers.TestObserver;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.ext.web.client.HttpResponse;
import io.vertx.reactivex.ext.web.client.WebClient;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * @author Yann TAVERNIER (yann.tavernier at graviteesource.com)
 * @author GraviteeSource Team
 */
@GatewayTest
class EndpointCalloutPolicyIntegrationTest
  extends AbstractPolicyTest<EndpointCalloutPolicy, EndpointCalloutPolicyConfiguration> {

  public static final String LOCALHOST = "localhost:";
  public static final String CALLOUT_BASE_URL = LOCALHOST + "8089";

  @RegisterExtension
  static WireMockExtension calloutServer = WireMockExtension
    .newInstance()
    .options(wireMockConfig().dynamicPort())
    .build();

  /**
   * Override Callout policy URL to use the dynamic port from {@link EndpointCalloutPolicyIntegrationTest#calloutServer}
   * @param api is the api to apply this function code
   */
  @Override
  public void configureApi(Api api) {
    api
      .getFlows()
      .forEach(flow -> {
        flow
          .getPre()
          .stream()
          .filter(step -> policyName().equals(step.getPolicy()))
          .forEach(step ->
            step.setConfiguration(
              step
                .getConfiguration()
                .replace(CALLOUT_BASE_URL, LOCALHOST + calloutServer.getPort())
            )
          );
      });
  }

  @Override
  public void configurePolicies(Map<String, PolicyPlugin> policies) {
    policies.put(
      "copy-callout-attribute",
      PolicyBuilder.build(
        "copy-callout-attribute",
        CopyCalloutAttributePolicy.class
      )
    );
  }

  @Test
  @DisplayName("Should do callout and set response as attribute")
  @DeployApi("/apis/endpoint-callout.json")
  void shouldDoCalloutAndSetResponseAsAttribute(WebClient client)
    throws Exception {
    wiremock.stubFor(get("/endpoint").willReturn(ok("response from backend")));
    calloutServer.stubFor(
      get("/callout").willReturn(ok("response from callout"))
    );

    final TestObserver<HttpResponse<Buffer>> obs = client
      .get("/test")
      .rxSend()
      .test();

    awaitTerminalEvent(obs);
    obs
      .assertComplete()
      .assertValue(response -> {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.bodyAsString()).isEqualTo("response from callout");
        return true;
      })
      .assertNoErrors();

    wiremock.verify(getRequestedFor(urlPathEqualTo("/endpoint")));
    calloutServer.verify(
      getRequestedFor(urlPathEqualTo("/callout"))
        .withHeader("X-Callout", equalTo("calloutHeader"))
    );
  }

  @Test
  @DisplayName("Should do callout Fire and Forget")
  @DeployApi("/apis/endpoint-callout-fire-and-forget.json")
  void shouldDoCalloutFireAndForget(WebClient client) throws Exception {
    wiremock.stubFor(get("/endpoint").willReturn(ok("response from backend")));
    calloutServer.stubFor(
      get("/callout").willReturn(ok("response from callout"))
    );

    final TestObserver<HttpResponse<Buffer>> obs = client
      .get("/test")
      .rxSend()
      .test();

    awaitTerminalEvent(obs);
    obs
      .assertComplete()
      .assertValue(response -> {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.bodyAsString())
          .isEqualTo(CopyCalloutAttributePolicy.NO_CALLOUT_CONTENT_ATTRIBUTE);
        return true;
      })
      .assertNoErrors();

    wiremock.verify(getRequestedFor(urlPathEqualTo("/endpoint")));
    calloutServer.verify(
      getRequestedFor(urlPathEqualTo("/callout"))
        .withHeader("X-Callout", equalTo("calloutHeader"))
    );
  }

  @Test
  @DisplayName("Should do callout on invalid target and answer custom response")
  @DeployApi("/apis/endpoint-callout-invalid-target.json")
  void shouldDoCalloutInvalidTarget(WebClient client) throws Exception {
    wiremock.stubFor(get("/endpoint").willReturn(ok("response from backend")));
    calloutServer.stubFor(
      get("/callout")
        .willReturn(
          aResponse()
            .withStatus(501)
            .withBody("callout backend not implemented")
        )
    );

    final TestObserver<HttpResponse<Buffer>> obs = client
      .get("/test")
      .rxSend()
      .test();

    awaitTerminalEvent(obs);
    obs
      .assertComplete()
      .assertValue(response -> {
        assertThat(response.statusCode())
          .isEqualTo(HttpStatusCode.NOT_IMPLEMENTED_501);
        assertThat(response.bodyAsString()).isEqualTo("errorContent");
        return true;
      })
      .assertNoErrors();

    wiremock.verify(0, getRequestedFor(urlPathEqualTo("/endpoint")));
    calloutServer.verify(
      getRequestedFor(urlPathEqualTo("/callout"))
        .withHeader("X-Callout", equalTo("calloutHeader"))
    );
  }
}
