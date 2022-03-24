/*
 * Copyright (c) 2011-2022 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.server;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.buffer.ByteBuf;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelFutureListeners;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.http.DefaultFullHttpRequest;
import io.netty5.handler.codec.http.EmptyLastHttpContent;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpHeaders;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty5.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty5.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty5.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty5.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty5.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty5.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.FutureListener;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.ReactorNetty;
import reactor.netty.http.HttpOperations;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;
import reactor.util.annotation.Nullable;

import static io.netty5.buffer.api.adaptor.ByteBufAdaptor.extractOrCopy;
import static reactor.netty.ReactorNetty.format;

/**
 * Conversion between Netty types  and Reactor types ({@link HttpOperations}
 *
 * @author Stephane Maldini
 * @author Simon Baslé
 */
final class WebsocketServerOperations extends HttpServerOperations
		implements WebsocketInbound, WebsocketOutbound {

	final WebSocketServerHandshaker           handshaker;
	final Future<Void>                        handshakerResult;
	final Sinks.One<WebSocketCloseStatus>     onCloseState;
	final boolean                             proxyPing;

	volatile int closeSent;

	@SuppressWarnings("FutureReturnValueIgnored")
	WebsocketServerOperations(String wsUrl, WebsocketServerSpec websocketServerSpec, HttpServerOperations replaced) {
		super(replaced);
		this.proxyPing = websocketServerSpec.handlePing();

		Channel channel = replaced.channel();
		onCloseState = Sinks.unsafe().one();

		// Handshake
		WebSocketServerHandshakerFactory wsFactory =
				new WebSocketServerHandshakerFactory(wsUrl, websocketServerSpec.protocols(), true, websocketServerSpec.maxFramePayloadLength());
		handshaker = wsFactory.newHandshaker(replaced.nettyRequest);
		if (handshaker == null) {
			//"FutureReturnValueIgnored" this is deliberate
			WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(channel);
			handshakerResult = null;
		}
		else {
			removeHandler(NettyPipeline.HttpTrafficHandler);
			removeHandler(NettyPipeline.AccessLogHandler);
			removeHandler(NettyPipeline.HttpMetricsHandler);

			HttpRequest request = new DefaultFullHttpRequest(replaced.version(), replaced.method(), replaced.uri(),
					channel.bufferAllocator().allocate(0));

			request.headers()
			       .set(replaced.nettyRequest.headers());

			if (websocketServerSpec.compress()) {
				removeHandler(NettyPipeline.CompressionHandler);

				WebSocketServerCompressionHandler wsServerCompressionHandler =
						new WebSocketServerCompressionHandler();
				try {
					wsServerCompressionHandler.channelRead(channel.pipeline()
					                                              .context(NettyPipeline.ReactiveBridge),
							request);

					addHandlerFirst(NettyPipeline.WsCompressionHandler, wsServerCompressionHandler);
				}
				catch (Throwable e) {
					log.error(format(channel(), ""), e);
				}
			}

			handshakerResult =
					handshaker.handshake(channel,
					                     request,
					                     replaced.responseHeaders
					                             .remove(HttpHeaderNames.TRANSFER_ENCODING))
					          .addListener(f -> {
					              if (replaced.rebind(this)) {
					                  markPersistent(false);
					                  // This change is needed after the Netty change https://github.com/netty/netty/pull/11966
					                  channel.read();
					              }
					              else {
					                  log.debug(format(channel, "Cannot bind WebsocketServerOperations after the handshake."));
					              }
					          });
		}
	}

	@Override
	public NettyOutbound send(Publisher<? extends ByteBuf> dataStream) {
		return sendObject(Flux.from(dataStream).map(byteBuf -> bytebufToWebsocketFrame.apply(extractOrCopy(bufferAlloc(), byteBuf))));
	}

	@Override
	public HttpHeaders headers() {
		return requestHeaders();
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void onInboundNext(ChannelHandlerContext ctx, Object frame) {
		if (frame instanceof CloseWebSocketFrame && ((CloseWebSocketFrame) frame).isFinalFragment()) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "CloseWebSocketFrame detected. Closing Websocket"));
			}
			CloseWebSocketFrame closeFrame = new CloseWebSocketFrame(true, ((CloseWebSocketFrame) frame).rsv(),
					((CloseWebSocketFrame) frame).binaryData());
			if (closeFrame.statusCode() != -1) {
				// terminate() will invoke onInboundComplete()
				sendCloseNow(closeFrame, f -> terminate());
			}
			else {
				// terminate() will invoke onInboundComplete()
				sendCloseNow(closeFrame, WebSocketCloseStatus.EMPTY, f -> terminate());
			}
			return;
		}
		if (!this.proxyPing && frame instanceof PingWebSocketFrame) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame) frame).binaryData()));
			ctx.read();
			return;
		}
		if (!(frame instanceof EmptyLastHttpContent)) {
			super.onInboundNext(ctx, frame);
		}
	}

	@Override
	protected void onOutboundComplete() {
	}

	@Override
	protected void onOutboundError(Throwable err) {
		if (channel().isActive()) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "Outbound error happened"), err);
			}
			sendCloseNow(new CloseWebSocketFrame(channel().bufferAllocator(), WebSocketCloseStatus.PROTOCOL_ERROR),
					f -> terminate());
		}
	}

	@Override
	protected void onInboundCancel() {
		if (log.isDebugEnabled()) {
			log.debug(format(channel(), "Cancelling Websocket inbound. Closing Websocket"));
		}
		sendCloseNow(new CloseWebSocketFrame(true, 0, channel().bufferAllocator().allocate(0)),
				WebSocketCloseStatus.ABNORMAL_CLOSURE, f -> terminate());
	}

	@Override
	public Mono<Void> sendClose() {
		return sendClose(new CloseWebSocketFrame(true, 0, channel().bufferAllocator().allocate(0)));
	}

	@Override
	public Mono<Void> sendClose(int rsv) {
		return sendClose(new CloseWebSocketFrame(channel().bufferAllocator(), true, rsv));
	}

	@Override
	public Mono<Void> sendClose(int statusCode, @Nullable String reasonText) {
		return sendClose(new CloseWebSocketFrame(channel().bufferAllocator(), statusCode, reasonText));
	}

	@Override
	public Mono<Void> sendClose(int rsv, int statusCode, @Nullable String reasonText) {
		return sendClose(new CloseWebSocketFrame(channel().bufferAllocator(), true, rsv, statusCode, reasonText));
	}

	@Override
	@SuppressWarnings("unchecked")
	public Mono<WebSocketCloseStatus> receiveCloseStatus() {
		return onCloseState.asMono().or((Mono) onTerminate());
	}

	Mono<Void> sendClose(CloseWebSocketFrame frame) {
		if (CLOSE_SENT.get(this) == 0) {
			//commented for now as we assume the close is always scheduled (deferFuture runs)
			//onTerminate().subscribe(null, null, () -> ReactorNetty.safeRelease(frame));
			return Mono.fromCompletionStage(() -> {
				if (CLOSE_SENT.getAndSet(this, 1) == 0) {
					discard();
					// EmitResult is ignored as CLOSE_SENT guarantees that there will be only one emission
					// Whether there are subscribers or the subscriber cancels is not of interest
					// Evaluated EmitResult: FAIL_TERMINATED, FAIL_OVERFLOW, FAIL_CANCELLED, FAIL_NON_SERIALIZED
					// FAIL_ZERO_SUBSCRIBER
					onCloseState.tryEmitValue(new WebSocketCloseStatus(frame.statusCode(), frame.reasonText()));
					return channel().writeAndFlush(frame)
					                .addListener(channel(), ChannelFutureListeners.CLOSE)
					                .asStage();
				}
				frame.close();
				return channel().newSucceededFuture().asStage();
			}).doOnCancel(() -> ReactorNetty.safeRelease(frame));
		}
		frame.close();
		return Mono.empty();
	}
	void sendCloseNow(CloseWebSocketFrame frame, FutureListener<Void> listener) {
		sendCloseNow(frame, new WebSocketCloseStatus(frame.statusCode(), frame.reasonText()), listener);
	}

	@SuppressWarnings("FutureReturnValueIgnored")
	void sendCloseNow(CloseWebSocketFrame frame, WebSocketCloseStatus closeStatus, FutureListener<Void> listener) {
		if (!frame.isFinalFragment()) {
			//"FutureReturnValueIgnored" this is deliberate
			channel().writeAndFlush(frame);
			return;
		}
		if (CLOSE_SENT.getAndSet(this, 1) == 0) {
			// EmitResult is ignored as CLOSE_SENT guarantees that there will be only one emission
			// Whether there are subscribers or the subscriber cancels is not of interest
			// Evaluated EmitResult: FAIL_TERMINATED, FAIL_OVERFLOW, FAIL_CANCELLED, FAIL_NON_SERIALIZED
			// FAIL_ZERO_SUBSCRIBER
			onCloseState.tryEmitValue(closeStatus);
			channel().writeAndFlush(frame)
			         .addListener(listener);
		}
		else {
			frame.close();
		}
	}

	@Override
	public boolean isWebsocket() {
		return true;
	}

	@Override
	@Nullable
	public String selectedSubprotocol() {
		return handshaker.selectedSubprotocol();
	}

	static final AtomicIntegerFieldUpdater<WebsocketServerOperations> CLOSE_SENT =
			AtomicIntegerFieldUpdater.newUpdater(WebsocketServerOperations.class,
					"closeSent");
}
