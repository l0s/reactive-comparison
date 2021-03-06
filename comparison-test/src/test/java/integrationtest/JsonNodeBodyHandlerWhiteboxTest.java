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

import static org.mockito.Mockito.spy;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;

import org.reactivestreams.tck.TestEnvironment;
import org.reactivestreams.tck.flow.FlowSubscriberWhiteboxVerification;
import org.testng.annotations.BeforeMethod;

import com.fasterxml.jackson.databind.ObjectMapper;

import integrationtest.JsonNodeBodyHandler.JsonNodeBodySubscriber;

class JsonNodeBodyHandlerWhiteboxTest extends FlowSubscriberWhiteboxVerification<List<ByteBuffer>> {

    public JsonNodeBodyHandlerWhiteboxTest() {
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

    protected Subscriber<List<ByteBuffer>> createFlowSubscriber(final WhiteboxSubscriberProbe<List<ByteBuffer>> probe) {
        final JsonNodeBodySubscriber delegate = handler.new JsonNodeBodySubscriber();
        final SubscriberPuppet puppet = new SubscriberPuppet() {

            public void triggerRequest(final long elements) {
                delegate.subscription.ifPresent(subscription -> subscription.request(elements));
            }

            public void signalCancel() {
                delegate.subscription.ifPresent(Subscription::cancel);
            }
        };
        
        return new Subscriber<List<ByteBuffer>>() {

            public void onComplete() {
                probe.registerOnComplete();
                delegate.onComplete();
            }

            public void onError(final Throwable throwable) {
                probe.registerOnError(throwable);
                delegate.onError(throwable);
            }

            public void onNext(final List<ByteBuffer> item) {
                probe.registerOnNext(item);
                delegate.onNext(item);
            }

            public void onSubscribe(final Subscription subscription) {
                probe.registerOnSubscribe(puppet);
                delegate.onSubscribe(subscription);
            }

        };
    }

    public List<ByteBuffer> createElement(int element) {
        return Arrays.asList(ByteBuffer.wrap("".getBytes(charset)));
    }

}