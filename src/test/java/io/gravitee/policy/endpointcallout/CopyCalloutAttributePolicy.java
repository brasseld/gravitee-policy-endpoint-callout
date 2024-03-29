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

import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.stream.BufferedReadWriteStream;
import io.gravitee.gateway.api.stream.ReadWriteStream;
import io.gravitee.gateway.api.stream.SimpleReadWriteStream;
import io.gravitee.policy.api.annotations.OnResponseContent;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class CopyCalloutAttributePolicy {

  public static final String NO_CALLOUT_CONTENT_ATTRIBUTE = "noCalloutContent";

  @OnResponseContent
  public ReadWriteStream<Buffer> onResponseContent(ExecutionContext context) {
    return new BufferedReadWriteStream() {
      @Override
      public SimpleReadWriteStream<Buffer> write(Buffer content) {
        return this;
      }

      @Override
      public void end() {
        String content = NO_CALLOUT_CONTENT_ATTRIBUTE;
        final Object calloutContent = context.getAttribute("calloutContent");
        if (calloutContent != null) {
          content = calloutContent.toString();
        }
        super.write(Buffer.buffer(content));
        super.end();
      }
    };
  }
}
