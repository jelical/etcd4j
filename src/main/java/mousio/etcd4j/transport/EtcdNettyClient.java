package mousio.etcd4j.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.GenericFutureListener;
import mousio.etcd4j.promises.EtcdResponsePromise;
import mousio.etcd4j.requests.EtcdKeyRequest;
import mousio.etcd4j.requests.EtcdRequest;
import mousio.etcd4j.requests.EtcdVersionRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * Netty client for the requests and responses
 */
public class EtcdNettyClient implements EtcdClientImpl {
  private final Bootstrap bootstrap;
  private final NioEventLoopGroup eventLoopGroup;

  private final URI[] uris;
  protected int lastWorkingUriIndex = 0;

  class MyChannelListener<R> implements GenericFutureListener<ChannelFuture>{
      private final ConnectionCounter _counter;
      private String _url;
      private Channel _channel;
      private EtcdRequest<R> _etcdRequest;

      MyChannelListener(EtcdRequest<R> etcdRequest, ConnectionCounter counter, String url,Channel channel ){
          _etcdRequest = etcdRequest;
          _counter = counter;
          _url = url;
          _channel = channel;
      }
      @Override
      public void operationComplete(ChannelFuture f) throws Exception {
          if (!f.isSuccess()) {
              _counter.uriIndex++;
              if (_counter.uriIndex >= uris.length) {
                  if (_counter.retryCount >= 3) {
                      _etcdRequest.getPromise().setException(f.cause());
                      return;
                  }
                  _counter.retryCount++;
                  _counter.uriIndex = 0;
              }

              connect(_etcdRequest, _counter, _url);
              return;
          }

          lastWorkingUriIndex = _counter.uriIndex;

          modifyPipeLine(_etcdRequest, f.channel().pipeline());

          HttpRequest httpRequest = createHttpRequest(_url, _etcdRequest);

          // send request
          _channel.writeAndFlush(httpRequest);
      }
  }


  /**
   * Constructor
   *
   * @param sslContext SSL context if connecting with SSL. Null if not connecting with SSL.
   * @param uri        to connect to
   */
  public EtcdNettyClient(final SslContext sslContext, URI... uri) {
    this.eventLoopGroup = new NioEventLoopGroup();

    this.uris = uri;

    // Configure the client.
    this.bootstrap = new Bootstrap();
    bootstrap.group(eventLoopGroup)
        .channel(NioSocketChannel.class)
        .option(ChannelOption.TCP_NODELAY, true)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 300)
        .handler(new ChannelInitializer<SocketChannel>() {
          @Override
          public void initChannel(SocketChannel ch) throws Exception {
            ChannelPipeline p = ch.pipeline();
            if (sslContext != null) {
              p.addLast(sslContext.newHandler(ch.alloc()));
            }
            p.addLast("codec", new HttpClientCodec());
            p.addLast("aggregate", new HttpObjectAggregator(1024 * 100));
          }
        });
  }

  /**
   * Send a request and get a future.
   *
   * @param etcdRequest Etcd Request to send
   * @return Promise for the request.
   */
  public <R> EtcdResponsePromise<R> send(EtcdRequest<R> etcdRequest) throws IOException {
    if (etcdRequest.getPromise() == null) {
      EtcdResponsePromise<R> responsePromise = new EtcdResponsePromise<R>();
      etcdRequest.setPromise(responsePromise);
    }

    connect(etcdRequest);

    return etcdRequest.getPromise();
  }

  /**
   * Connect
   *
   * @param request to connect with
   * @param <R>     Type of response
   * @throws IOException if connection fails
   */
  public <R> void connect(EtcdRequest<R> request) throws IOException {
    connect(request, request.getUri());
  }

  /**
   * Connect
   *
   * @param request to request with
   * @param url     relative url to read resource at
   * @param <R>     Type of response
   * @throws IOException if request could not be sent.
   */
  public <R> void connect(EtcdRequest<R> request, String url) throws IOException {
    final ConnectionCounter counter = new ConnectionCounter();
    counter.uriIndex = lastWorkingUriIndex;

    connect(request, counter, url);
  }



  /**
   * Connect to server
   *
   * @param etcdRequest to request with
   * @param counter     for retries
   * @param url         relative url to read resource at
   * @param <R>         Type of response
   * @throws IOException if request could not be sent.
   */
  protected <R> void connect(EtcdRequest<R> etcdRequest, ConnectionCounter counter, String url) throws IOException {
    // Start the connection attempt.
    ChannelFuture connectFuture = bootstrap.clone()
        .connect(uris[counter.uriIndex].getHost(), uris[counter.uriIndex].getPort());

    Channel channel = connectFuture.channel();
    etcdRequest.getPromise().attachNettyPromise(
        new DefaultPromise<R>(connectFuture.channel().eventLoop())
    );

    connectFuture.addListener(new MyChannelListener<R>(etcdRequest,counter,url,channel));
  }

  /**
   * Modify the pipeline for the request
   *
   * @param req      to process
   * @param pipeline to modify
   * @param <R>      Type of Response
   */
  @SuppressWarnings("unchecked")
  private <R> void modifyPipeLine(final EtcdRequest<R> req, ChannelPipeline pipeline) {
    if (req.getTimeout() != -1) {
      pipeline.addFirst(new ChannelHandlerAdapter() {
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
          req.getPromise().getNettyPromise().setFailure(cause);
        }
      });
      pipeline.addFirst(new ReadTimeoutHandler(req.getTimeout(), req.getTimeoutUnit()));
    }

    if (req instanceof EtcdKeyRequest) {
      pipeline.addLast(
          new EtcdKeyResponseHandler(this, (EtcdKeyRequest) req)
      );
    } else if (req instanceof EtcdVersionRequest) {
      pipeline.addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) throws Exception {
          ((EtcdVersionRequest) req).getPromise().getNettyPromise()
              .setSuccess(
                      msg.content().toString(Charset.defaultCharset()));
        }
      });
    } else {
      throw new RuntimeException("Unknown request type " + req.getClass().getName());
    }
  }

  /**
   * Get HttpRequest belonging to etcdRequest
   *
   * @param uri         to send request to
   * @param etcdRequest to send
   * @param <R>         Response type
   * @return HttpRequest
   * @throws IOException if request could not be created
   */
  public static <R> HttpRequest createHttpRequest(String uri, EtcdRequest<R> etcdRequest) throws IOException {
    HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, etcdRequest.getMethod(), uri);
    httpRequest.headers().add("Connection", "keep-alive");
    try {
      httpRequest = setRequestParameters(etcdRequest, httpRequest);
    } catch (Exception e) {
      throw new IOException(e);
    }
    return httpRequest;
  }

  /**
   * Set parameters on request
   *
   * @param etcdRequest to send
   * @param httpRequest to send
   * @return Http Request
   * @throws Exception on fail
   */
  private static HttpRequest setRequestParameters(EtcdRequest<?> etcdRequest, HttpRequest httpRequest) throws Exception {
    // Set possible key value pairs
    Map<String, String> keyValuePairs = etcdRequest.getRequestParams();
    if (keyValuePairs != null && !keyValuePairs.isEmpty()) {
      if (etcdRequest.getMethod() == HttpMethod.POST) {
        HttpPostRequestEncoder bodyRequestEncoder = new HttpPostRequestEncoder(httpRequest, false);
        for (Map.Entry<String, String> entry : keyValuePairs.entrySet()) {
          bodyRequestEncoder.addBodyAttribute(entry.getKey(), entry.getValue());
        }

        httpRequest = bodyRequestEncoder.finalizeRequest();
        bodyRequestEncoder.close();
      } else {
        String getLocation = "";
        for (Map.Entry<String, String> entry : keyValuePairs.entrySet()) {
          if (!getLocation.isEmpty()) {
            getLocation += "&";
          }
          getLocation += entry.getKey() + "=" + entry.getValue();
        }

        httpRequest.setUri(etcdRequest.getUri().concat("?").concat(getLocation));
      }
    }
    return httpRequest;
  }

  /**
   * Close netty
   */
  public void close() {
    eventLoopGroup.shutdownGracefully();
  }

  /**
   * Counts connection retries and current connection index
   */
  protected class ConnectionCounter {
    public int uriIndex;
    public int retryCount;
  }
}