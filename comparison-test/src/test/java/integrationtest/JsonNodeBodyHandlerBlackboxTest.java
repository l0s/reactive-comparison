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
