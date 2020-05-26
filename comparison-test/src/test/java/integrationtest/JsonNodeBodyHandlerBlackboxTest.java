/**
 * Copyright © 2020 Carlos Macasaet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package integrationtest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Flow.Subscriber;

import org.reactivestreams.tck.TestEnvironment;
import org.reactivestreams.tck.flow.FlowSubscriberBlackboxVerification;
import org.testng.annotations.BeforeMethod;

import com.fasterxml.jackson.databind.ObjectMapper;

class JsonNodeBodyHandlerBlackboxTest extends FlowSubscriberBlackboxVerification<List<ByteBuffer>> {

    protected JsonNodeBodyHandlerBlackboxTest() {
        super(new TestEnvironment());
    }

    private static final Charset charset = Charset.forName("UTF-8");
    private ObjectMapper mapper;
    private JsonNodeBodyHandler handler;

    @BeforeMethod
    public void setUpFixtures() {
        mapper = spy(new ObjectMapper());
        handler = new JsonNodeBodyHandler(mapper);
    }

    public Subscriber<List<ByteBuffer>> createFlowSubscriber() {
        final ResponseInfo responseInfo = mock(ResponseInfo.class);
        return handler.apply(responseInfo);
    }

    public List<ByteBuffer> createElement(int element) {
        return Arrays.asList(ByteBuffer.wrap("".getBytes(charset)));
    }

}
