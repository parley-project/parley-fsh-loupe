/**
 * <PRE>
 *
 * Copyright Tony Bringarder 1998, 2026 All Rights Reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 */
package us.bringardner.fsh.loupe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.loupe.LoupeConnection;
import us.bringardner.parley.loupe.LoupeEndpoint;
import us.bringardner.parley.loupe.LoupeException;
import us.bringardner.parley.loupe.protocol.Hello;
import us.bringardner.parley.loupe.protocol.Loupe;

/** The adapter's --listen: where it listens, what it warns of, and that it serves a debugger. */
public class TestFshAdapterMain {

	@Test
	public void aPortAloneIsOnTheLoopbackAddress() throws Exception {
		try (ServerSocket server = FshAdapterMain.open("0")) {
			assertTrue(server.getInetAddress().isLoopbackAddress());
			assertTrue(server.getLocalPort() > 0);
			// only this machine can connect: nothing to warn of
			assertNull(FshAdapterMain.warning(server));
		}
		try (ServerSocket server = FshAdapterMain.open("127.0.0.1:0")) {
			assertTrue(server.getInetAddress().isLoopbackAddress());
			assertNull(FshAdapterMain.warning(server));
		}
	}

	@Test
	public void anAddressListensThereAndWarns() throws Exception {
		for(String where : new String[] {"0.0.0.0:0", "*:0", " 0.0.0.0 : 0 "}) {
			try (ServerSocket server = FshAdapterMain.open(where)) {
				assertTrue(server.getInetAddress().isAnyLocalAddress(), where);
				String warning = FshAdapterMain.warning(server);
				assertNotNull(warning, where);
				assertTrue(warning.contains("every address of this machine") && warning.contains("port "+server.getLocalPort())
						&& warning.contains("no login and no encryption"), warning);
			}
		}
		// one address of this machine: named in the warning
		InetAddress own = InetAddress.getLocalHost();
		if( !own.isLoopbackAddress()) {
			try (ServerSocket server = FshAdapterMain.open(own.getHostAddress()+":0")) {
				assertEquals(own, server.getInetAddress());
				assertTrue(FshAdapterMain.warning(server).contains(own.getHostAddress()));
			}
		}
	}

	@Test
	public void whatIsNotAPortOrAddressAndPort() {
		for(String bad : new String[] {"", "abc", "host:", ":4711", "host:port", "70000", "-1", null}) {
			assertThrows(IllegalArgumentException.class, ()->FshAdapterMain.open(bad).close(), String.valueOf(bad));
		}
	}

	/** A debugger connects to the listening adapter, twice (one at a time), and closing the socket ends it. */
	@Test
	public void servesDebuggersUntilItIsClosed() throws Exception {
		ServerSocket server = FshAdapterMain.open("0");
		CompletableFuture<Void> served = CompletableFuture.runAsync(()->{
			try {
				FshAdapterMain.serve(server);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		try {
			for(int i=0; i < 2; i++ ) {
				try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
					LoupeConnection client = LoupeConnection.open(new LoupeEndpoint.Handler() {
						@Override
						public CompletableFuture<Object> request(String method, String target, Object params) {
							throw new LoupeException(LoupeException.UNKNOWN_METHOD, method);
						}

						@Override
						public void event(String event, String target, Object params) {
						}
					}, socket.getInputStream(), socket.getOutputStream());
					try {
						@SuppressWarnings("unchecked")
						Map<String,Object> welcome = (Map<String,Object>) client.getEndpoint().request("hello", null,
								new Hello().setProtocols(List.of("loupe/1")).setFeatures(List.of(Loupe.FEATURE_SNAPSHOT)).toJson())
								.get(20, TimeUnit.SECONDS);
						assertEquals("loupe/1", welcome.get("protocol"));
						assertFalse(served.isDone());
					} finally {
						client.close();
					}
				}
			}
		} finally {
			server.close();
		}
		served.get(20, TimeUnit.SECONDS);
	}

	/**
	 * The port takes a DAP debugger too (told from its first message), and what isn't a debugger
	 * at all is dropped without ending the adapter.
	 */
	@Test
	public void theSamePortServesADapDebugger() throws Exception {
		ServerSocket server = FshAdapterMain.open("0");
		CompletableFuture<Void> served = CompletableFuture.runAsync(()->{
			try {
				FshAdapterMain.serve(server);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		try {
			// not a debugger: a line of text, then gone
			try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
				socket.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
				socket.getOutputStream().flush();
			}
			// and one that connects and says nothing
			new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort()).close();

			java.util.concurrent.BlockingQueue<String> events = new java.util.concurrent.LinkedBlockingQueue<>();
			try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
				us.bringardner.parley.dap.DapConnection client = us.bringardner.parley.dap.DapConnection.client(
						new us.bringardner.parley.dap.protocol.IDebugClient() {
							@Override
							public void initialized() {
								events.add("initialized");
							}

							@Override
							public void output(us.bringardner.parley.dap.protocol.OutputEventBody b) {
								events.add("output "+b.getOutput());
							}

							@Override
							public void exited(us.bringardner.parley.dap.protocol.ExitedEventBody b) {
								events.add("exited "+b.getExitCode());
							}
						}, socket.getInputStream(), socket.getOutputStream());
				try {
					us.bringardner.parley.dap.protocol.IDebugAdapter dap = client.getAdapter();
					assertNotNull(dap.initialize(new us.bringardner.parley.dap.protocol.InitializeRequestArguments().setAdapterID("fsh")).get(20, TimeUnit.SECONDS));
					dap.launch(new us.bringardner.parley.dap.protocol.LaunchRequestArguments()
							.setExtra(new java.util.LinkedHashMap<>(Map.of("source", "echo over dap\nexit 4\n", "name", "d.fsh")))).get(20, TimeUnit.SECONDS);
					dap.configurationDone(new us.bringardner.parley.dap.protocol.ConfigurationDoneArguments()).get(20, TimeUnit.SECONDS);
					StringBuilder seen = new StringBuilder();
					for(;;) {
						String e = events.poll(20, TimeUnit.SECONDS);
						assertNotNull(e, "no exit; saw "+seen);
						seen.append(e).append('|');
						if( e.startsWith("exited")) {
							assertEquals("exited 4", e);
							break;
						}
					}
					assertTrue(seen.toString().contains("output over dap"), seen.toString());
				} finally {
					client.close();
				}
			}
			assertFalse(served.isDone());
		} finally {
			server.close();
		}
		served.get(20, TimeUnit.SECONDS);
	}
}
