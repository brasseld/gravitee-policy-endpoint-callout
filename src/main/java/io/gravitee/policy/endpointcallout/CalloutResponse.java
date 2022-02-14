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

import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.api.proxy.ProxyResponse;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class CalloutResponse {

  private final ProxyResponse response;
  private final String content;
  private final HttpHeaders headers;

  CalloutResponse(final ProxyResponse response) {
    this(response, null);
  }

  CalloutResponse(final ProxyResponse response, final String content) {
    this.response = response;
    this.content = content;
    this.headers = HttpHeaders.create(response.headers());
  }

  public int getStatus() {
    return response.status();
  }

  public HttpHeaders getHeaders() {
    return headers;
  }

  public String getContent() {
    return content;
  }
}
