/*
 * Copyright (C) 2017 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import okhttp3.RecordingEventListener.CallEnd;
import okhttp3.RecordingEventListener.CallFailed;
import okhttp3.RecordingEventListener.ConnectEnd;
import okhttp3.RecordingEventListener.ConnectFailed;
import okhttp3.RecordingEventListener.ConnectStart;
import okhttp3.RecordingEventListener.ConnectionAcquired;
import okhttp3.RecordingEventListener.DnsEnd;
import okhttp3.RecordingEventListener.DnsStart;
import okhttp3.RecordingEventListener.RequestBodyEnd;
import okhttp3.RecordingEventListener.RequestHeadersEnd;
import okhttp3.RecordingEventListener.ResponseBodyEnd;
import okhttp3.RecordingEventListener.ResponseFailed;
import okhttp3.RecordingEventListener.ResponseHeadersEnd;
import okhttp3.RecordingEventListener.SecureConnectEnd;
import okhttp3.RecordingEventListener.SecureConnectStart;
import okhttp3.internal.DoubleInetAddressDns;
import okhttp3.internal.Internal;
import okhttp3.internal.RecordingOkAuthenticator;
import okhttp3.logging.HttpLoggingInterceptor;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import okio.Buffer;
import okio.BufferedSink;
import org.assertj.core.api.Assertions;
import org.hamcrest.BaseMatcher;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static java.util.Arrays.asList;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.hamcrest.CoreMatchers.any;
import static org.hamcrest.CoreMatchers.either;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

public final class EventListenerTest {
  public static final Matcher<Response> anyResponse = CoreMatchers.any(Response.class);
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private final RecordingEventListener listener = new RecordingEventListener();
  private final HandshakeCertificates handshakeCertificates = localhost();

  private OkHttpClient client = clientTestRule.client;
  private SocksProxy socksProxy;

  @Before public void setUp() {
    client = clientTestRule.client.newBuilder()
        .eventListener(listener)
        .build();

    listener.forbidLock(Internal.instance.realConnectionPool(client.connectionPool()));
    listener.forbidLock(client.dispatcher());
  }

  @After public void tearDown() throws Exception {
    if (socksProxy != null) {
      socksProxy.shutdown();
    }
  }

  @Test public void successfulCallEventSequence() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    Assertions.assertThat(response.body().string()).isEqualTo("abc");
    response.body().close();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  @Test public void successfulCallEventSequenceForEnqueue() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());

    final CountDownLatch completionLatch = new CountDownLatch(1);
    Callback callback = new Callback() {
      @Override public void onFailure(Call call, IOException e) {
        completionLatch.countDown();
      }

      @Override public void onResponse(Call call, Response response) {
        response.close();
        completionLatch.countDown();
      }
    };

    call.enqueue(callback);

    completionLatch.await();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  @Test public void failedCallEventSequence() {
    server.enqueue(new MockResponse().setHeadersDelay(2, TimeUnit.SECONDS));

    client = client.newBuilder().readTimeout(250, TimeUnit.MILLISECONDS).build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage(), either(equalTo("timeout")).or(equalTo("Read timed out")));
    }

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseFailed", "ConnectionReleased",
        "CallFailed");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  @Test public void failedDribbledCallEventSequence() throws IOException {
    server.enqueue(new MockResponse().setBody("0123456789")
        .throttleBody(2, 100, TimeUnit.MILLISECONDS)
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

    client = client.newBuilder()
        .protocols(Collections.singletonList(Protocol.HTTP_1_1))
        .readTimeout(250, TimeUnit.MILLISECONDS)
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());

    Response response = call.execute();
    try {
      response.body.string();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage(), equalTo("unexpected end of stream"));
    }

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseFailed", "ConnectionReleased", "CallFailed");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
    ResponseFailed responseFailed = listener.removeUpToEvent(ResponseFailed.class);
    Assertions.assertThat(responseFailed.ioe.getMessage()).isEqualTo(
        "unexpected end of stream");
  }

  @Test public void canceledCallEventSequence() {
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    call.cancel();
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
      Assertions.assertThat(expected.getMessage()).isEqualTo("Canceled");
    }

    List<String> expectedEvents = Arrays.asList("CallStart", "CallFailed");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  private void assertSuccessfulEventOrder(Matcher<Response> responseMatcher) throws IOException {
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().string();
    response.body().close();

    assumeThat(response, responseMatcher);

    List<String> expectedEvents = asList("CallStart", "DnsStart", "DnsEnd", "ConnectStart",
        "SecureConnectStart", "SecureConnectEnd", "ConnectEnd", "ConnectionAcquired",
        "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd",
        "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased", "CallEnd");

    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  @Test public void secondCallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build()).execute().close();

    listener.removeUpToEvent(CallEnd.class);

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    response.close();

    List<String> expectedEvents = asList("CallStart", "ConnectionAcquired",
        "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd",
        "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased", "CallEnd");

    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  private void assertBytesReadWritten(RecordingEventListener listener,
      @Nullable Matcher<Long> requestHeaderLength, @Nullable Matcher<Long> requestBodyBytes,
      @Nullable Matcher<Long> responseHeaderLength, @Nullable Matcher<Long> responseBodyBytes) {

    if (requestHeaderLength != null) {
      RequestHeadersEnd responseHeadersEnd = listener.removeUpToEvent(RequestHeadersEnd.class);
      assertThat("request header length", responseHeadersEnd.headerLength, requestHeaderLength);
    } else {
      Assertions.assertThat(listener.recordedEventTypes().contains("RequestHeadersEnd")).overridingErrorMessage(
          "Found RequestHeadersEnd").isFalse();
    }

    if (requestBodyBytes != null) {
      RequestBodyEnd responseBodyEnd = listener.removeUpToEvent(RequestBodyEnd.class);
      assertThat("request body bytes", responseBodyEnd.bytesWritten, requestBodyBytes);
    } else {
      Assertions.assertThat(listener.recordedEventTypes().contains("RequestBodyEnd")).overridingErrorMessage(
          "Found RequestBodyEnd").isFalse();
    }

    if (responseHeaderLength != null) {
      ResponseHeadersEnd responseHeadersEnd = listener.removeUpToEvent(ResponseHeadersEnd.class);
      assertThat("response header length", responseHeadersEnd.headerLength, responseHeaderLength);
    } else {
      Assertions.assertThat(listener.recordedEventTypes().contains("ResponseHeadersEnd")).overridingErrorMessage(
          "Found ResponseHeadersEnd").isFalse();
    }

    if (responseBodyBytes != null) {
      ResponseBodyEnd responseBodyEnd = listener.removeUpToEvent(ResponseBodyEnd.class);
      assertThat("response body bytes", responseBodyEnd.bytesRead, responseBodyBytes);
    } else {
      Assertions.assertThat(listener.recordedEventTypes().contains("ResponseBodyEnd")).overridingErrorMessage(
          "Found ResponseBodyEnd").isFalse();
    }
  }

  private Matcher<Long> greaterThan(final long value) {
    return new BaseMatcher<Long>() {
      @Override public void describeTo(Description description) {
        description.appendText("> " + value);
      }

      @Override public boolean matches(Object o) {
        return ((Long)o) > value;
      }
    };
  }

  private Matcher<Long> lessThan(final long value) {
    return new BaseMatcher<Long>() {
      @Override public void describeTo(Description description) {
        description.appendText("< " + value);
      }

      @Override public boolean matches(Object o) {
        return ((Long)o) < value;
      }
    };
  }

  private Matcher<Response> matchesProtocol(final Protocol protocol) {
    return new BaseMatcher<Response>() {
      @Override public void describeTo(Description description) {
        description.appendText("is HTTP/2");
      }

      @Override public boolean matches(Object o) {
        return ((Response)o).protocol == protocol;
      }
    };
  }

  @Test public void successfulEmptyH2CallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
    server.enqueue(new MockResponse());

    assertSuccessfulEventOrder(matchesProtocol(Protocol.HTTP_2));

    assertBytesReadWritten(listener, any(Long.class), null, greaterThan(0L),
        equalTo(0L));
  }

  @Test public void successfulEmptyHttpsCallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_1_1));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    assertSuccessfulEventOrder(anyResponse);

    assertBytesReadWritten(listener, any(Long.class), null, greaterThan(0L),
        equalTo(3L));
  }

  @Test public void successfulChunkedHttpsCallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_1_1));
    server.enqueue(
        new MockResponse().setBodyDelay(100, TimeUnit.MILLISECONDS).setChunkedBody("Hello!", 2));

    assertSuccessfulEventOrder(anyResponse);

    assertBytesReadWritten(listener, any(Long.class), null, greaterThan(0L),
        equalTo(6L));
  }

  @Test public void successfulChunkedH2CallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
    server.enqueue(
        new MockResponse().setBodyDelay(100, TimeUnit.MILLISECONDS).setChunkedBody("Hello!", 2));

    assertSuccessfulEventOrder(matchesProtocol(Protocol.HTTP_2));

    assertBytesReadWritten(listener, any(Long.class), null, equalTo(0L),
        greaterThan(6L));
  }

  @Test public void successfulDnsLookup() throws IOException {
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    DnsStart dnsStart = listener.removeUpToEvent(DnsStart.class);
    Assertions.assertThat(dnsStart.call).isSameAs(call);
    Assertions.assertThat(dnsStart.domainName).isEqualTo(server.getHostName());

    DnsEnd dnsEnd = listener.removeUpToEvent(DnsEnd.class);
    Assertions.assertThat(dnsEnd.call).isSameAs(call);
    Assertions.assertThat(dnsEnd.domainName).isEqualTo(server.getHostName());
    Assertions.assertThat(dnsEnd.inetAddressList.size()).isEqualTo(1);
  }

  @Test public void noDnsLookupOnPooledConnection() throws IOException {
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    // Seed the pool.
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();
    Assertions.assertThat(response1.code()).isEqualTo(200);
    response1.body().close();

    listener.clearAllEvents();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    Assertions.assertThat(response2.code()).isEqualTo(200);
    response2.body().close();

    List<String> recordedEvents = listener.recordedEventTypes();
    Assertions.assertThat(recordedEvents.contains("DnsStart")).isFalse();
    Assertions.assertThat(recordedEvents.contains("DnsEnd")).isFalse();
  }

  @Test public void multipleDnsLookupsForSingleCall() throws IOException {
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .setHeader("Location", "http://www.fakeurl:" + server.getPort()));
    server.enqueue(new MockResponse());

    FakeDns dns = new FakeDns();
    dns.set("fakeurl", client.dns().lookup(server.getHostName()));
    dns.set("www.fakeurl", client.dns().lookup(server.getHostName()));

    client = client.newBuilder()
        .dns(dns)
        .build();

    Call call = client.newCall(new Request.Builder()
        .url("http://fakeurl:" + server.getPort())
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    listener.removeUpToEvent(DnsStart.class);
    listener.removeUpToEvent(DnsEnd.class);
    listener.removeUpToEvent(DnsStart.class);
    listener.removeUpToEvent(DnsEnd.class);
  }

  @Test public void failedDnsLookup() {
    client = client.newBuilder()
        .dns(new FakeDns())
        .build();
    Call call = client.newCall(new Request.Builder()
        .url("http://fakeurl/")
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    listener.removeUpToEvent(DnsStart.class);

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    Assertions.assertThat(callFailed.call).isSameAs(call);
    boolean condition = callFailed.ioe instanceof UnknownHostException;
    Assertions.assertThat(condition).isTrue();
  }

  @Test public void emptyDnsLookup() {
    Dns emptyDns = hostname -> Collections.emptyList();

    client = client.newBuilder()
        .dns(emptyDns)
        .build();
    Call call = client.newCall(new Request.Builder()
        .url("http://fakeurl/")
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    listener.removeUpToEvent(DnsStart.class);

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    Assertions.assertThat(callFailed.call).isSameAs(call);
    boolean condition = callFailed.ioe instanceof UnknownHostException;
    Assertions.assertThat(condition).isTrue();
  }

  @Test public void successfulConnect() throws IOException {
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    InetAddress address = client.dns().lookup(server.getHostName()).get(0);
    InetSocketAddress expectedAddress = new InetSocketAddress(address, server.getPort());

    ConnectStart connectStart = listener.removeUpToEvent(ConnectStart.class);
    Assertions.assertThat(connectStart.call).isSameAs(call);
    Assertions.assertThat(connectStart.inetSocketAddress).isEqualTo(expectedAddress);
    Assertions.assertThat(connectStart.proxy).isEqualTo(Proxy.NO_PROXY);

    ConnectEnd connectEnd = listener.removeUpToEvent(ConnectEnd.class);
    Assertions.assertThat(connectEnd.call).isSameAs(call);
    Assertions.assertThat(connectEnd.inetSocketAddress).isEqualTo(expectedAddress);
    Assertions.assertThat(connectEnd.protocol).isEqualTo(Protocol.HTTP_1_1);
  }

  @Test public void failedConnect() throws UnknownHostException {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    InetAddress address = client.dns().lookup(server.getHostName()).get(0);
    InetSocketAddress expectedAddress = new InetSocketAddress(address, server.getPort());

    ConnectStart connectStart = listener.removeUpToEvent(ConnectStart.class);
    Assertions.assertThat(connectStart.call).isSameAs(call);
    Assertions.assertThat(connectStart.inetSocketAddress).isEqualTo(expectedAddress);
    Assertions.assertThat(connectStart.proxy).isEqualTo(Proxy.NO_PROXY);

    ConnectFailed connectFailed = listener.removeUpToEvent(ConnectFailed.class);
    Assertions.assertThat(connectFailed.call).isSameAs(call);
    Assertions.assertThat(connectFailed.inetSocketAddress).isEqualTo(expectedAddress);
    Assertions.assertThat(connectFailed.protocol).isNull();
    Assertions.assertThat(connectFailed.ioe).isNotNull();
  }

  @Test public void multipleConnectsForSingleCall() throws IOException {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    listener.removeUpToEvent(ConnectStart.class);
    listener.removeUpToEvent(ConnectFailed.class);
    listener.removeUpToEvent(ConnectStart.class);
    listener.removeUpToEvent(ConnectEnd.class);
  }

  @Test public void successfulHttpProxyConnect() throws IOException {
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .proxy(server.toProxyAddress())
        .build();

    Call call = client.newCall(new Request.Builder()
        .url("http://www.fakeurl")
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    InetAddress address = client.dns().lookup(server.getHostName()).get(0);
    InetSocketAddress expectedAddress = new InetSocketAddress(address, server.getPort());

    ConnectStart connectStart = listener.removeUpToEvent(ConnectStart.class);
    Assertions.assertThat(connectStart.call).isSameAs(call);
    Assertions.assertThat(connectStart.inetSocketAddress).isEqualTo(expectedAddress);
    Assertions.assertThat(connectStart.proxy).isEqualTo(server.toProxyAddress());

    ConnectEnd connectEnd = listener.removeUpToEvent(ConnectEnd.class);
    Assertions.assertThat(connectEnd.call).isSameAs(call);
    Assertions.assertThat(connectEnd.inetSocketAddress).isEqualTo(expectedAddress);
    Assertions.assertThat(connectEnd.protocol).isEqualTo(Protocol.HTTP_1_1);
  }

  @Test public void successfulSocksProxyConnect() throws Exception {
    server.enqueue(new MockResponse());

    socksProxy = new SocksProxy();
    socksProxy.play();
    Proxy proxy = socksProxy.proxy();

    client = client.newBuilder()
        .proxy(proxy)
        .build();

    Call call = client.newCall(new Request.Builder()
        .url("http://" + SocksProxy.HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS + ":" + server.getPort())
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    InetSocketAddress expectedAddress = InetSocketAddress.createUnresolved(
        SocksProxy.HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS, server.getPort());

    ConnectStart connectStart = listener.removeUpToEvent(ConnectStart.class);
    Assertions.assertThat(connectStart.call).isSameAs(call);
    Assertions.assertThat(connectStart.inetSocketAddress).isEqualTo(expectedAddress);
    Assertions.assertThat(connectStart.proxy).isEqualTo(proxy);

    ConnectEnd connectEnd = listener.removeUpToEvent(ConnectEnd.class);
    Assertions.assertThat(connectEnd.call).isSameAs(call);
    Assertions.assertThat(connectEnd.inetSocketAddress).isEqualTo(expectedAddress);
    Assertions.assertThat(connectEnd.protocol).isEqualTo(Protocol.HTTP_1_1);
  }

  @Test public void authenticatingTunnelProxyConnect() throws IOException {
    enableTlsWithTunnel(true);
    server.enqueue(new MockResponse()
        .setResponseCode(407)
        .addHeader("Proxy-Authenticate: Basic realm=\"localhost\"")
        .addHeader("Connection: close"));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END));
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .proxy(server.toProxyAddress())
        .proxyAuthenticator(new RecordingOkAuthenticator("password", "Basic"))
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    listener.removeUpToEvent(ConnectStart.class);

    ConnectEnd connectEnd = listener.removeUpToEvent(ConnectEnd.class);
    Assertions.assertThat(connectEnd.protocol).isNull();

    listener.removeUpToEvent(ConnectStart.class);
    listener.removeUpToEvent(ConnectEnd.class);
  }

  @Test public void successfulSecureConnect() throws IOException {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    SecureConnectStart secureStart = listener.removeUpToEvent(SecureConnectStart.class);
    Assertions.assertThat(secureStart.call).isSameAs(call);

    SecureConnectEnd secureEnd = listener.removeUpToEvent(SecureConnectEnd.class);
    Assertions.assertThat(secureEnd.call).isSameAs(call);
    Assertions.assertThat(secureEnd.handshake).isNotNull();
  }

  @Test public void failedSecureConnect() {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    SecureConnectStart secureStart = listener.removeUpToEvent(SecureConnectStart.class);
    Assertions.assertThat(secureStart.call).isSameAs(call);

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    Assertions.assertThat(callFailed.call).isSameAs(call);
    Assertions.assertThat(callFailed.ioe).isNotNull();
  }

  @Test public void secureConnectWithTunnel() throws IOException {
    enableTlsWithTunnel(true);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END));
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .proxy(server.toProxyAddress())
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    SecureConnectStart secureStart = listener.removeUpToEvent(SecureConnectStart.class);
    Assertions.assertThat(secureStart.call).isSameAs(call);

    SecureConnectEnd secureEnd = listener.removeUpToEvent(SecureConnectEnd.class);
    Assertions.assertThat(secureEnd.call).isSameAs(call);
    Assertions.assertThat(secureEnd.handshake).isNotNull();
  }

  @Test public void multipleSecureConnectsForSingleCall() throws IOException {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    listener.removeUpToEvent(SecureConnectStart.class);
    listener.removeUpToEvent(ConnectFailed.class);

    listener.removeUpToEvent(SecureConnectStart.class);
    listener.removeUpToEvent(SecureConnectEnd.class);
  }

  @Test public void noSecureConnectsOnPooledConnection() throws IOException {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .build();

    // Seed the pool.
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();
    Assertions.assertThat(response1.code()).isEqualTo(200);
    response1.body().close();

    listener.clearAllEvents();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    Assertions.assertThat(response2.code()).isEqualTo(200);
    response2.body().close();

    List<String> recordedEvents = listener.recordedEventTypes();
    Assertions.assertThat(recordedEvents.contains("SecureConnectStart")).isFalse();
    Assertions.assertThat(recordedEvents.contains("SecureConnectEnd")).isFalse();
  }

  @Test public void successfulConnectionFound() throws IOException {
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    ConnectionAcquired connectionAcquired = listener.removeUpToEvent(ConnectionAcquired.class);
    Assertions.assertThat(connectionAcquired.call).isSameAs(call);
    Assertions.assertThat(connectionAcquired.connection).isNotNull();
  }

  @Test public void noConnectionFoundOnFollowUp() throws IOException {
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .addHeader("Location", "/foo"));
    server.enqueue(new MockResponse()
        .setBody("ABC"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.body().string()).isEqualTo("ABC");

    listener.removeUpToEvent(ConnectionAcquired.class);

    List<String> remainingEvents = listener.recordedEventTypes();
    Assertions.assertThat(remainingEvents.contains("ConnectionAcquired")).isFalse();
  }

  @Test public void pooledConnectionFound() throws IOException {
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    // Seed the pool.
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();
    Assertions.assertThat(response1.code()).isEqualTo(200);
    response1.body().close();

    ConnectionAcquired connectionAcquired1 = listener.removeUpToEvent(ConnectionAcquired.class);
    listener.clearAllEvents();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    Assertions.assertThat(response2.code()).isEqualTo(200);
    response2.body().close();

    ConnectionAcquired connectionAcquired2 = listener.removeUpToEvent(ConnectionAcquired.class);
    Assertions.assertThat(connectionAcquired2.connection).isSameAs(
        connectionAcquired1.connection);
  }

  @Test public void multipleConnectionsFoundForSingleCall() throws IOException {
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .addHeader("Location", "/foo")
        .addHeader("Connection", "Close"));
    server.enqueue(new MockResponse()
        .setBody("ABC"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.body().string()).isEqualTo("ABC");

    listener.removeUpToEvent(ConnectionAcquired.class);
    listener.removeUpToEvent(ConnectionAcquired.class);
  }

  @Test public void responseBodyFailHttp1OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_1_1));
    responseBodyFail(Protocol.HTTP_1_1);
  }

  @Test public void responseBodyFailHttp2OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
    responseBodyFail(Protocol.HTTP_2);
  }

  @Test public void responseBodyFailHttp() throws IOException {
    responseBodyFail(Protocol.HTTP_1_1);
  }

  private void responseBodyFail(Protocol expectedProtocol) throws IOException {
    // Use a 2 MiB body so the disconnect won't happen until the client has read some data.
    int responseBodySize = 2 * 1024 * 1024; // 2 MiB
    server.enqueue(new MockResponse()
        .setBody(new Buffer().write(new byte[responseBodySize]))
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    if (expectedProtocol == Protocol.HTTP_2) {
      // soft failure since client may not support depending on Platform
      assumeThat(response, matchesProtocol(Protocol.HTTP_2));
    }
    Assertions.assertThat(response.protocol()).isEqualTo(expectedProtocol);
    try {
      response.body.string();
      fail();
    } catch (IOException expected) {
    }

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    Assertions.assertThat(callFailed.ioe).isNotNull();
  }

  @Test public void emptyResponseBody() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("")
        .setBodyDelay(1, TimeUnit.SECONDS)
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    response.body().close();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  @Test public void emptyResponseBodyConnectionClose() throws IOException {
    server.enqueue(new MockResponse()
        .addHeader("Connection", "close")
        .setBody(""));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    response.body().close();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  @Test public void responseBodyClosedClosedWithoutReadingAllData() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("abc")
        .setBodyDelay(1, TimeUnit.SECONDS)
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    response.body().close();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  @Test public void requestBodyFailHttp1OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_1_1));
    requestBodyFail();
  }

  @Test public void requestBodyFailHttp2OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
    requestBodyFail();
  }

  @Test public void requestBodyFailHttp() throws IOException {
    requestBodyFail();
  }

  private void requestBodyFail() {
    // Stream a 256 MiB body so the disconnect will happen before the server has read everything.
    RequestBody requestBody = new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.get("text/plain");
      }

      @Override public long contentLength() {
        return 1024 * 1024 * 256;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        for (int i = 0; i < 1024; i++) {
          sink.write(new byte[1024 * 256]);
          sink.flush();
        }
      }
    };

    server.setBodyLimit(0L);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(requestBody)
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    Assertions.assertThat(callFailed.ioe).isNotNull();
  }

  @Test public void requestBodyMultipleFailuresReportedOnlyOnce() {
    RequestBody requestBody = new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.get("text/plain");
      }

      @Override public long contentLength() {
        return 1024 * 1024 * 256;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        int failureCount = 0;
        for (int i = 0; i < 1024; i++) {
          try {
            sink.write(new byte[1024 * 256]);
            sink.flush();
          } catch (IOException e) {
            failureCount++;
            if (failureCount == 3) throw e;
          }
        }
      }
    };

    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(requestBody)
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd", "ConnectStart",
        "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart", "RequestHeadersEnd",
        "RequestBodyStart", "RequestFailed", "ConnectionReleased", "CallFailed");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  @Test public void requestBodySuccessHttp1OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_1_1));
    requestBodySuccess(RequestBody.create(MediaType.get("text/plain"), "Hello"), equalTo(5L),
        equalTo(19L));
  }

  @Test public void requestBodySuccessHttp2OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
    requestBodySuccess(RequestBody.create(MediaType.get("text/plain"), "Hello"), equalTo(5L),
        equalTo(19L));
  }

  @Test public void requestBodySuccessHttp() throws IOException {
    requestBodySuccess(RequestBody.create(MediaType.get("text/plain"), "Hello"), equalTo(5L),
        equalTo(19L));
  }

  @Test public void requestBodySuccessStreaming() throws IOException {
    RequestBody requestBody = new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.get("text/plain");
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.write(new byte[8192]);
        sink.flush();
      }
    };

    requestBodySuccess(requestBody, equalTo(8192L), equalTo(19L));
  }

  @Test public void requestBodySuccessEmpty() throws IOException {
    requestBodySuccess(RequestBody.create(MediaType.get("text/plain"), ""), equalTo(0L),
        equalTo(19L));
  }

  @Test public void successfulCallEventSequenceWithListener() throws IOException {
    server.enqueue(new MockResponse().setBody("abc"));

    client = client.newBuilder()
        .addNetworkInterceptor(new HttpLoggingInterceptor()
            .setLevel(HttpLoggingInterceptor.Level.BODY))
        .build();
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    Assertions.assertThat(response.body().string()).isEqualTo("abc");
    response.body().close();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  private void requestBodySuccess(RequestBody body, Matcher<Long> requestBodyBytes,
      Matcher<Long> responseHeaderLength) throws IOException {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("World!"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(body)
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.body().string()).isEqualTo("World!");

    assertBytesReadWritten(listener, any(Long.class), requestBodyBytes, responseHeaderLength,
        equalTo(6L));
  }

  private void enableTlsWithTunnel(boolean tunnelProxy) {
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();
    server.useHttps(handshakeCertificates.sslSocketFactory(), tunnelProxy);
  }

  @Test public void redirectUsingSameConnectionEventSequence() throws IOException {
    server.enqueue(
        new MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
            .addHeader("Location: /foo"));
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    call.execute();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart",
        "ResponseHeadersEnd", "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased",
        "CallEnd");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  @Test
  public void redirectUsingNewConnectionEventSequence() throws IOException {
    MockWebServer otherServer = new MockWebServer();
    server.enqueue(
        new MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
            .addHeader("Location: " + otherServer.url("/foo")));
    otherServer.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "DnsStart", "DnsEnd", "ConnectStart", "ConnectEnd",
        "ConnectionAcquired", "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart",
        "ResponseHeadersEnd", "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased",
        "CallEnd");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  @Test public void applicationInterceptorProceedsMultipleTimes() throws Exception {
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    client = client.newBuilder()
        .addInterceptor(chain -> {
          try (Response a = chain.proceed(chain.request())) {
            Assertions.assertThat(a.body().string()).isEqualTo("a");
          }
          return chain.proceed(chain.request());
        })
        .build();

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    Assertions.assertThat(response.body().string()).isEqualTo("b");

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart",
        "ResponseHeadersEnd", "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased",
        "CallEnd");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);

    Assertions.assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    Assertions.assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @Test public void applicationInterceptorShortCircuit() throws Exception {
    client = client.newBuilder()
        .addInterceptor(chain -> new Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(ResponseBody.create(null, "a"))
            .build())
        .build();

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    Assertions.assertThat(response.body().string()).isEqualTo("a");

    List<String> expectedEvents = Arrays.asList("CallStart", "CallEnd");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  /** Response headers start, then the entire request body, then response headers end. */
  @Test public void expectContinueStartsResponseHeadersEarly() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.EXPECT_CONTINUE));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post(RequestBody.create(MediaType.get("text/plain"), "abc"))
        .build();

    Call call = client.newCall(request);
    call.execute();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd", "ConnectStart",
        "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart", "RequestHeadersEnd",
        "ResponseHeadersStart", "RequestBodyStart", "RequestBodyEnd", "ResponseHeadersEnd",
        "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }
}
