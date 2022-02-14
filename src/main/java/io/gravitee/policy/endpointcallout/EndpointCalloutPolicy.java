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

import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.el.EvaluableRequest;
import io.gravitee.gateway.api.el.EvaluableResponse;
import io.gravitee.gateway.api.endpoint.resolver.EndpointResolver;
import io.gravitee.gateway.api.endpoint.resolver.ProxyEndpoint;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.proxy.ProxyConnection;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.gateway.api.proxy.ProxyResponse;
import io.gravitee.gateway.api.proxy.builder.ProxyRequestBuilder;
import io.gravitee.gateway.api.stream.BufferedReadWriteStream;
import io.gravitee.gateway.api.stream.ReadWriteStream;
import io.gravitee.gateway.api.stream.SimpleReadWriteStream;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.policy.api.annotations.OnRequestContent;
import io.gravitee.policy.api.annotations.OnResponse;
import io.gravitee.policy.api.annotations.OnResponseContent;
import io.gravitee.policy.endpointcallout.configuration.EndpointCalloutPolicyConfiguration;
import io.gravitee.policy.endpointcallout.configuration.PolicyScope;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class EndpointCalloutPolicy {

  private static final Logger LOGGER = LoggerFactory.getLogger(
    EndpointCalloutPolicy.class
  );

  private static final String ENDPOINT_CALLOUT_EXIT_ON_ERROR =
    "ENDPOINT_CALLOUT_EXIT_ON_ERROR";
  private static final String ENDPOINT_CALLOUT_ERROR = "ENDPOINT_CALLOUT_ERROR";

  private final EndpointCalloutPolicyConfiguration configuration;

  private static final String TEMPLATE_VARIABLE = "calloutResponse";

  private static final String REQUEST_TEMPLATE_VARIABLE = "request";
  private static final String RESPONSE_TEMPLATE_VARIABLE = "response";

  public EndpointCalloutPolicy(
    final EndpointCalloutPolicyConfiguration configuration
  ) {
    this.configuration = configuration;
  }

  @OnRequest
  public void onRequest(
    io.gravitee.gateway.api.Request request,
    Response response,
    ExecutionContext context,
    PolicyChain policyChain
  ) {
    if (
      configuration.getScope() == null ||
      configuration.getScope() == PolicyScope.REQUEST
    ) {
      initRequestResponseProperties(context);

      doCallout(
        context,
        __ -> policyChain.doNext(request, response),
        policyChain::failWith
      );
    } else {
      policyChain.doNext(request, response);
    }
  }

  @OnResponse
  public void onResponse(
    Request request,
    Response response,
    ExecutionContext context,
    PolicyChain policyChain
  ) {
    if (configuration.getScope() == PolicyScope.RESPONSE) {
      initRequestResponseProperties(context);

      doCallout(
        context,
        __ -> policyChain.doNext(request, response),
        policyChain::failWith
      );
    } else {
      policyChain.doNext(request, response);
    }
  }

  private void doCallout(
    ExecutionContext context,
    Consumer<Void> onSuccess,
    Consumer<PolicyResult> onError
  ) {
    final Consumer<Void> onSuccessCallback;
    final Consumer<PolicyResult> onErrorCallback;

    if (configuration.isFireAndForget()) {
      // If fire & forget, continue the chaining before making the http callout.
      onSuccess.accept(null);

      // callBacks need to be replaced because the fire & forget mode does not allow to act on the request / response once the http call as been performed.
      onSuccessCallback = aVoid -> {};
      onErrorCallback = policyResult -> {};
    } else {
      // Preserve original callback when not in fire & forget mode.
      onSuccessCallback = onSuccess;
      onErrorCallback = onError;
    }

    try {
      String url = context.getTemplateEngine().convert(configuration.getUrl());

      final EndpointResolver endpointResolver = context.getComponent(
        EndpointResolver.class
      );

      final String target = context
        .getTemplateEngine()
        .getValue(url, String.class);
      ProxyEndpoint endpoint = endpointResolver.resolve(target);

      String body = null;

      if (
        configuration.getBody() != null && !configuration.getBody().isEmpty()
      ) {
        // Body can be dynamically resolved using el expression.
        body =
          context
            .getTemplateEngine()
            .getValue(configuration.getBody(), String.class);
      }

      // Check the resolved body before trying to send it.
      //if (body != null && !body.isEmpty()) {
      //    httpClientRequest.headers().remove(HttpHeaders.TRANSFER_ENCODING);
      //    httpClientRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(body.length()));
      //}

      ProxyRequest proxyRequest = endpoint.createProxyRequest(
        context.request(),
        new Function<ProxyRequestBuilder, ProxyRequestBuilder>() {
          @Override
          public ProxyRequestBuilder apply(ProxyRequestBuilder builder) {
            builder.method(configuration.getMethod());
            builder.uri(target);
            // TODO: handle headers

            return builder;
          }
        }
      );

      String finalBody = body;
      endpoint
        .connector()
        .request(
          proxyRequest,
          context,
          new Handler<ProxyConnection>() {
            @Override
            public void handle(ProxyConnection connection) {
              if (connection != null) {
                if (finalBody != null && !finalBody.isEmpty()) {
                  connection.end(Buffer.buffer(finalBody));
                } else {
                  connection.end();
                }

                connection.exceptionHandler(
                  new Handler<Throwable>() {
                    @Override
                    public void handle(Throwable throwable) {
                      handleFailure(
                        onSuccessCallback,
                        onErrorCallback,
                        throwable
                      );
                    }
                  }
                );

                connection.responseHandler(
                  new Handler<ProxyResponse>() {
                    @Override
                    public void handle(ProxyResponse proxyResponse) {
                      handleSuccess(
                        context,
                        onSuccessCallback,
                        onErrorCallback,
                        proxyResponse
                      );
                    }
                  }
                );
              } else {
                handleFailure(
                  onSuccessCallback,
                  onErrorCallback,
                  new Exception()
                );
              }
            }
          }
        );
      /*

            HttpClient httpClient = vertx.createHttpClient(options);

            RequestOptions requestOpts = new RequestOptions()
                    .setAbsoluteURI(url)
                    .setMethod(convert(configuration.getMethod()));

            final Future<HttpClientRequest> futureRequest = httpClient.request(requestOpts);

            futureRequest.onFailure(throwable -> handleFailure(onSuccessCallback, onErrorCallback, httpClient, throwable));

            futureRequest.onSuccess(httpClientRequest -> {
                // Connection is made, lets continue.
                final Future<HttpClientResponse> futureResponse;

                if (configuration.getHeaders() != null) {
                    configuration.getHeaders().forEach(header -> {
                        try {
                            String extValue = (header.getValue() != null) ?
                                    context.getTemplateEngine().convert(header.getValue()) : null;
                            if (extValue != null) {
                                httpClientRequest.putHeader(header.getName(), extValue);
                            }
                        } catch (Exception ex) {
                            // Do nothing
                        }
                    });
                }

                String body = null;

                if (configuration.getBody() != null && !configuration.getBody().isEmpty()) {
                    // Body can be dynamically resolved using el expression.
                    body = context.getTemplateEngine()
                            .getValue(configuration.getBody(), String.class);
                }

                // Check the resolved body before trying to send it.
                if(body != null && !body.isEmpty()) {
                    httpClientRequest.headers().remove(HttpHeaders.TRANSFER_ENCODING);
                    httpClientRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(body.length()));
                    futureResponse = httpClientRequest.send(io.vertx.core.buffer.Buffer.buffer(body));
                } else {
                    futureResponse = httpClientRequest.send();
                }

                futureResponse
                        .onSuccess(httpResponse -> handleSuccess(context, onSuccessCallback, onErrorCallback, httpClient, httpResponse))
                        .onFailure(throwable -> handleFailure(onSuccessCallback, onErrorCallback, httpClient, throwable));
            });
             */

    } catch (Exception ex) {
      onErrorCallback.accept(
        PolicyResult.failure(
          ENDPOINT_CALLOUT_ERROR,
          "Unable to apply expression language on the configured URL"
        )
      );
    }
  }

  private void handleSuccess(
    ExecutionContext context,
    Consumer<Void> onSuccess,
    Consumer<PolicyResult> onError,
    ProxyResponse proxyResponse
  ) {
    proxyResponse.bodyHandler(
      body -> {
        TemplateEngine tplEngine = context.getTemplateEngine();

        // Put response into template variable for EL
        final CalloutResponse calloutResponse = new CalloutResponse(
          proxyResponse,
          body.toString()
        );

        if (!configuration.isFireAndForget()) {
          // Variables and exit on error are only managed if the fire & forget is disabled.
          tplEngine
            .getTemplateContext()
            .setVariable(TEMPLATE_VARIABLE, calloutResponse);

          // Process callout response
          boolean exit = false;

          if (configuration.isExitOnError()) {
            exit =
              tplEngine.getValue(
                configuration.getErrorCondition(),
                Boolean.class
              );
          }

          if (!exit) {
            // Set context variables
            if (configuration.getVariables() != null) {
              configuration
                .getVariables()
                .forEach(
                  variable -> {
                    try {
                      String extValue = (variable.getValue() != null)
                        ? tplEngine.getValue(variable.getValue(), String.class)
                        : null;

                      context.setAttribute(variable.getName(), extValue);
                    } catch (Exception ex) {
                      // Do nothing
                    }
                  }
                );
            }

            tplEngine.getTemplateContext().setVariable(TEMPLATE_VARIABLE, null);

            // Finally continue chaining
            onSuccess.accept(null);
          } else {
            String errorContent = configuration.getErrorContent();
            try {
              errorContent =
                tplEngine.getValue(
                  configuration.getErrorContent(),
                  String.class
                );
            } catch (Exception ex) {
              // Do nothing
            }

            if (errorContent == null || errorContent.isEmpty()) {
              errorContent = "Request is terminated.";
            }

            onError.accept(
              PolicyResult.failure(
                ENDPOINT_CALLOUT_EXIT_ON_ERROR,
                configuration.getErrorStatusCode(),
                errorContent
              )
            );
          }
        }
      }
    );
  }

  private void handleFailure(
    Consumer<Void> onSuccess,
    Consumer<PolicyResult> onError,
    Throwable throwable
  ) {
    if (configuration.isExitOnError()) {
      // exit chain only if policy ask ExitOnError
      onError.accept(
        PolicyResult.failure(ENDPOINT_CALLOUT_ERROR, throwable.getMessage())
      );
    } else {
      // otherwise continue chaining
      onSuccess.accept(null);
    }
  }

  @OnRequestContent
  public ReadWriteStream onRequestContent(
    ExecutionContext context,
    PolicyChain policyChain
  ) {
    if (configuration.getScope() == PolicyScope.REQUEST_CONTENT) {
      return createStream(PolicyScope.REQUEST_CONTENT, context, policyChain);
    }

    return null;
  }

  @OnResponseContent
  public ReadWriteStream onResponseContent(
    ExecutionContext context,
    PolicyChain policyChain
  ) {
    if (configuration.getScope() == PolicyScope.RESPONSE_CONTENT) {
      return createStream(PolicyScope.RESPONSE_CONTENT, context, policyChain);
    }

    return null;
  }

  private ReadWriteStream createStream(
    PolicyScope scope,
    ExecutionContext context,
    PolicyChain policyChain
  ) {
    return new BufferedReadWriteStream() {
      io.gravitee.gateway.api.buffer.Buffer buffer = io.gravitee.gateway.api.buffer.Buffer.buffer();

      @Override
      public SimpleReadWriteStream<Buffer> write(
        io.gravitee.gateway.api.buffer.Buffer content
      ) {
        buffer.appendBuffer(content);
        return this;
      }

      @Override
      public void end() {
        initRequestResponseProperties(
          context,
          (scope == PolicyScope.REQUEST_CONTENT) ? buffer.toString() : null,
          (scope == PolicyScope.RESPONSE_CONTENT) ? buffer.toString() : null
        );

        doCallout(
          context,
          result -> {
            if (buffer.length() > 0) {
              super.write(buffer);
            }

            super.end();
          },
          policyChain::streamFailWith
        );
      }
    };
  }

  private void initRequestResponseProperties(ExecutionContext context) {
    initRequestResponseProperties(context, null, null);
  }

  private void initRequestResponseProperties(
    ExecutionContext context,
    String requestContent,
    String responseContent
  ) {
    context
      .getTemplateEngine()
      .getTemplateContext()
      .setVariable(
        REQUEST_TEMPLATE_VARIABLE,
        new EvaluableRequest(context.request(), requestContent)
      );

    context
      .getTemplateEngine()
      .getTemplateContext()
      .setVariable(
        RESPONSE_TEMPLATE_VARIABLE,
        new EvaluableResponse(context.response(), responseContent)
      );
  }
}
