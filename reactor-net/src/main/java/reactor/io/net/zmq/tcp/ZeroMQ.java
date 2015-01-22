/*
 * Copyright (c) 2011-2014 Pivotal Software, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package reactor.io.net.zmq.tcp;

import com.gs.collections.api.list.MutableList;
import com.gs.collections.impl.block.function.checked.CheckedFunction0;
import com.gs.collections.impl.block.predicate.checked.CheckedPredicate;
import com.gs.collections.impl.list.mutable.FastList;
import com.gs.collections.impl.list.mutable.SynchronizedMutableList;
import com.gs.collections.impl.map.mutable.SynchronizedMutableMap;
import com.gs.collections.impl.map.mutable.UnifiedMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import reactor.Environment;
import reactor.bus.EventBus;
import reactor.core.Dispatcher;
import reactor.core.support.Assert;
import reactor.core.support.NamedDaemonThreadFactory;
import reactor.io.buffer.Buffer;
import reactor.io.codec.Codec;
import reactor.io.codec.StandardCodecs;
import reactor.io.net.NetChannelStream;
import reactor.io.net.tcp.TcpClient;
import reactor.io.net.tcp.TcpServer;
import reactor.io.net.tcp.spec.TcpClientSpec;
import reactor.io.net.tcp.spec.TcpServerSpec;
import reactor.io.net.zmq.ZeroMQClientSocketOptions;
import reactor.io.net.zmq.ZeroMQServerSocketOptions;
import reactor.rx.Promise;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author Jon Brisbin
 */
public class ZeroMQ<T> {

	private static final SynchronizedMutableMap<Integer, String> SOCKET_TYPES = SynchronizedMutableMap.of(UnifiedMap
			.<Integer, String>newMap());

	private final Logger                       log     = LoggerFactory.getLogger(getClass());
	private final MutableList<TcpClient<T, T>> clients = SynchronizedMutableList.of(FastList.<TcpClient<T, T>>newList());
	private final MutableList<TcpServer<T, T>> servers = SynchronizedMutableList.of(FastList.<TcpServer<T, T>>newList());

	private final ExecutorService threadPool = Executors.newCachedThreadPool(new NamedDaemonThreadFactory("zmq"));

	private final Environment env;
	private final Dispatcher  dispatcher;
	private final EventBus    reactor;
	private final ZContext    zmqCtx;

	@SuppressWarnings("unchecked")
	private volatile Codec<Buffer, T, T> codec    = (Codec<Buffer, T, T>) StandardCodecs.PASS_THROUGH_CODEC;
	private volatile boolean             shutdown = false;

	public ZeroMQ(Environment env) {
		this(env, env.getDefaultDispatcher());
	}

	public ZeroMQ(Environment env, String dispatcher) {
		this(env, env.getDispatcher(dispatcher));
	}

	public ZeroMQ(Environment env, Dispatcher dispatcher) {
		this.env = env;
		this.dispatcher = dispatcher;
		this.reactor = EventBus.create(env, dispatcher);
		this.zmqCtx = new ZContext();
		this.zmqCtx.setLinger(100);
	}

	public static String findSocketTypeName(final int socketType) {
		return SOCKET_TYPES.getIfAbsentPut(socketType, new CheckedFunction0<String>() {
			@Override
			public String safeValue() throws Exception {
				for (Field f : ZMQ.class.getDeclaredFields()) {
					if (int.class.isAssignableFrom(f.getType())) {
						f.setAccessible(true);
						try {
							int val = f.getInt(null);
							if (socketType == val) {
								return f.getName();
							}
						} catch (IllegalAccessException e) {
						}
					}
				}
				return "";
			}
		});
	}

	public ZeroMQ<T> codec(Codec<Buffer, T, T> codec) {
		this.codec = codec;
		return this;
	}

	public Promise<NetChannelStream<T, T>> dealer(String addrs) {
		return createClient(addrs, ZMQ.DEALER);
	}

	public Promise<NetChannelStream<T, T>> push(String addrs) {
		return createClient(addrs, ZMQ.PUSH);
	}

	public Promise<NetChannelStream<T, T>> pull(String addrs) {
		return createServer(addrs, ZMQ.PULL);
	}

	public Promise<NetChannelStream<T, T>> request(String addrs) {
		return createClient(addrs, ZMQ.REQ);
	}

	public Promise<NetChannelStream<T, T>> reply(String addrs) {
		return createServer(addrs, ZMQ.REP);
	}

	public Promise<NetChannelStream<T, T>> router(String addrs) {
		return createServer(addrs, ZMQ.ROUTER);
	}

	public Promise<NetChannelStream<T, T>> createClient(String addrs, int socketType) {
		Assert.isTrue(!shutdown, "This ZeroMQ instance has been shut down");

		TcpClient<T, T> client = new TcpClientSpec<T, T>(ZeroMQTcpClient.class)
				.env(env).dispatcher(dispatcher).codec(codec)
				.options(new ZeroMQClientSocketOptions()
						.context(zmqCtx)
						.connectAddresses(addrs)
						.socketType(socketType))
				.get();

		clients.add(client);

		return client.open();
	}

	public Promise<NetChannelStream<T, T>> createServer(String addrs, int socketType) {
		Assert.isTrue(!shutdown, "This ZeroMQ instance has been shut down");

		TcpServer<T, T> server = new TcpServerSpec<T, T>(ZeroMQTcpServer.class)
				.env(env).dispatcher(dispatcher).codec(codec)
				.options(new ZeroMQServerSocketOptions()
						.context(zmqCtx)
						.listenAddresses(addrs)
						.socketType(socketType))
				.get();
		
		Promise<NetChannelStream<T, T>> d = server.next();

		servers.add(server);

		server.start();

		return d;
	}

	public void shutdown() {
		if (shutdown) {
			return;
		}
		shutdown = true;

		servers.removeIf(new CheckedPredicate<TcpServer<T, T>>() {
			@Override
			public boolean safeAccept(TcpServer<T, T> server) throws Exception {
				Promise<Void> promise = server.shutdown();
				promise.await(60, TimeUnit.SECONDS);
				Assert.isTrue(promise.isSuccess(), "Server " + server + " not properly shut down");
				return true;
			}
		});
		clients.removeIf(new CheckedPredicate<TcpClient<T, T>>() {
			@Override
			public boolean safeAccept(TcpClient<T, T> client) throws Exception {
				Promise<Void> promise = client.close();
				promise.await(60, TimeUnit.SECONDS);
				Assert.isTrue(promise.isSuccess(), "Client " + client + " not properly shut down");
				return true;
			}
		});

//		if (log.isDebugEnabled()) {
//			log.debug("Destroying {} on {}", zmqCtx, this);
//		}
//		zmqCtx.destroy();
	}

}
