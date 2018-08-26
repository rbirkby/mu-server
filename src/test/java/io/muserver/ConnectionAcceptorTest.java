package io.muserver;

import okhttp3.Response;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scaffolding.RawClient;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class ConnectionAcceptorTest {
    private static final Logger log = LoggerFactory.getLogger(ConnectionAcceptorTest.class);
    private static final ExecutorService executorService = Executors.newCachedThreadPool();

    @Test
    public void go() throws Exception {

        MuHandler echoHandler = new MuHandler() {
            @Override
            public boolean handle(MuRequest request, MuResponse response) throws Exception {
                response.contentType("text/plain");
                response.write("This is just a test");
                return true;
            }
        };
        ConnectionAcceptor selector = new ConnectionAcceptor(executorService, singletonList(echoHandler), null, new AtomicReference<>());
        selector.start("localhost", 0);

        URI targetURI = URI.create("http://localhost:" + selector.address.getPort());
        RawClient client = RawClient.create(targetURI);

        String message = "Hello, world";

        client.sendStartLine("GET", "/something?aquery=what&huh");
        client.sendHeader("Host", targetURI.getAuthority());
        client.sendHeader("Content-Length", String.valueOf(message.getBytes(UTF_8).length));
        client.endHeaders();
        client.sendUTF8(message);
        client.flushRequest();

        Thread.sleep(2000);

        long l = client.bytesReceived();
        byte[] resp = client.asBytes();
        String theString = client.responseString();

        System.out.println("Got back " + client.responseString());

        client.closeRequest();
        client.closeResponse();
    }

    @Test
    public void keepAliveTest() {
        assertThat(MuSelector.keepAlive(HttpVersion.HTTP_1_0, new MuHeaders()), is(false));
        assertThat(MuSelector.keepAlive(HttpVersion.HTTP_1_1, new MuHeaders()), is(true));
        assertThat(MuSelector.keepAlive(HttpVersion.HTTP_1_0, new MuHeaders().set("Connection", "keep-alive")), is(true));
        assertThat(MuSelector.keepAlive(HttpVersion.HTTP_1_1, new MuHeaders().set("Connection", "Blah, close, Another")), is(false));
    }

    @Test
    public void worksWithOKHttpClient() throws Exception {
        SSLContext sslContext = SSLContextBuilder.unsignedLocalhostCert();
        MuHandler echoHandler = new MuHandler() {
            @Override
            public boolean handle(MuRequest request, MuResponse response) throws Exception {
                response.contentType("text/plain");
                response.write("This is just a test");
                return true;
            }
        };
        ConnectionAcceptor selector = new ConnectionAcceptor(executorService, asList(echoHandler), sslContext, new AtomicReference<>());
        try {
            selector.start("localhost", 0);
            String targetURI = "https://localhost:" + selector.address.getPort();
            long start = System.currentTimeMillis();


            int numToMake = 1000;
            for (int i = 0; i < numToMake; i++) {
                try (Response resp = call(request().url(targetURI))) {
                    assertThat(resp.code(), is(200));
                    assertThat(resp.body().string(), equalTo("This is just a test"));
                }
            }

            long duration = System.currentTimeMillis() - start;
            System.out.println("Call took " + duration + "ms - or " + (duration / numToMake));
        } finally {
            selector.stop();
        }

    }

    /*
    Tests

    point https call at http endpoint
    point http call at https endpoint


     */

}