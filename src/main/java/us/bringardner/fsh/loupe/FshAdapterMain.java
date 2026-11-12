/**
 * <PRE>
 *
 * Copyright Tony Bringardner 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
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
 *
 *
 *	@author Tony Bringardner
 *
 *
 */
package us.bringardner.fsh.loupe;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

import us.bringardner.parley.loupe.AdapterHandler;
import us.bringardner.parley.loupe.LoupeConnection;
import us.bringardner.parley.loupe.LoupeEndpoint;
import us.bringardner.parley.loupe.protocol.Loupe;
import us.bringardner.parley.loupe.protocol.PeerInfo;

/**
 * The Loupe adapter for fsh. With no arguments it speaks Loupe on its standard input and
 * output, so {@code DapBridgeMain --exec java -cp ... us.bringardner.fsh.loupe.FshAdapterMain}
 * is a DAP debugger for fsh scripts; {@code --listen port} accepts connections one at a time
 * on the loopback address instead (only programs on this machine can connect).
 * <p>
 * {@code --listen address:port} listens on that address of this machine, for a debugger on
 * another machine: an address of one of its network interfaces, or {@code 0.0.0.0} (or
 * {@code *}) for all of them. The connection has no login and no encryption, and a debugger
 * can run any command as the user the adapter runs as, so it is only for a network where
 * everyone who can reach the port is trusted; the adapter says so on standard error when it
 * starts. An IPv6 address is written in brackets: {@code [::1]:4711}.
 */
public final class FshAdapterMain {

	private FshAdapterMain() {
	}

	/** A handler for a connection; every connection gets its own backend (one script each). */
	public static AdapterHandler handler(LoupeEndpoint endpoint) {
		return handler(endpoint, new FshBackend());
	}

	/**
	 * A handler for a connection that uses backend: one that launches (the caller can then tell
	 * it when the connection closes), or one for a script waiting for its debugger, which
	 * answers attach.
	 */
	public static AdapterHandler handler(LoupeEndpoint endpoint, FshBackend backend) {
		AdapterHandler ret = new AdapterHandler(endpoint, backend)
				.setAdapter(new PeerInfo().setName("fsh-loupe").setVersion("1"))
				.setFeatures(List.of(Loupe.FEATURE_SNAPSHOT))
				.setLaunchSchema(FshBackend.launchSchema())
				.setFallback(new LoupeEndpoint.Handler() {
					@Override
					public java.util.concurrent.CompletableFuture<Object> request(String method, String target, Object params) {
						return backend.extension(method, target, params);
					}

					@Override
					public void event(String event, String target, Object params) {
						// none are expected
					}
				});
		if( backend.isAttaching()) {
			ret.setAttachSchema(FshBackend.attachSchema());
		}
		return ret;
	}

	/**
	 * Opens the socket --listen names: "port" on the loopback address, or "address:port" on
	 * that address of this machine ("0.0.0.0:port" or "*:port" for every address; an IPv6
	 * address in brackets). Port 0 takes a free port.
	 *
	 * @throws IllegalArgumentException if where isn't a port or address:port
	 */
	public static ServerSocket open(String where) throws IOException {
		String text = where == null ? "" : where.trim();
		int colon = text.lastIndexOf(':');
		String host = colon < 0 ? null : text.substring(0, colon).trim();
		int port;
		try {
			port = Integer.parseInt(text.substring(colon+1).trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Expected port or address:port, not '"+where+"'");
		}
		if( port < 0 || port > 65535 || host != null && host.isEmpty()) {
			throw new IllegalArgumentException("Expected port or address:port, not '"+where+"'");
		}
		InetAddress address;
		if( host == null ) {
			address = InetAddress.getLoopbackAddress();
		} else if( host.equals("*")) {
			address = InetAddress.getByName("0.0.0.0");
		} else {
			if( host.startsWith("[") && host.endsWith("]")) {
				host = host.substring(1, host.length()-1);
			}
			address = InetAddress.getByName(host);
		}
		return new ServerSocket(port, 1, address);
	}

	/**
	 * What the adapter says on standard error when it listens where other machines can reach
	 * it, or null for a loopback address (only this machine can connect).
	 */
	public static String warning(ServerSocket server) {
		InetAddress a = server.getInetAddress();
		if( a == null || a.isLoopbackAddress()) {
			return null;
		}
		return "fsh-loupe: listening on "+(a.isAnyLocalAddress() ? "every address of this machine" : a.getHostAddress())
				+", port "+server.getLocalPort()+", with no login and no encryption: anyone who can reach this port can run"
				+" commands here as "+System.getProperty("user.name")+".";
	}

	/** Serves connections to server one at a time (each its own script), until server is closed. */
	public static void serve(ServerSocket server) throws IOException {
		for(;;) {
			Socket s;
			try {
				s = server.accept();
			} catch (IOException e) {
				if( server.isClosed()) {
					return;
				}
				throw e;
			}
			try (Socket socket = s) {
				FshBackend backend = new FshBackend();
				LoupeConnection c = LoupeConnection.serve(e -> handler(e, backend), socket.getInputStream(), socket.getOutputStream());
				c.closed().join();
				// (the debugger has gone: its script does not run on unseen)
				backend.connectionClosed();
			}
		}
	}

	public static void main(String[] args) throws IOException {
		if( args.length == 2 && args[0].equals("--listen")) {
			ServerSocket opened;
			try {
				opened = open(args[1]);
			} catch (IllegalArgumentException | java.net.UnknownHostException e) {
				System.err.println("fsh-loupe: "+e.getMessage());
				System.exit(2);
				return;
			}
			try (ServerSocket server = opened) {
				String warning = warning(server);
				if( warning != null ) {
					System.err.println(warning);
				}
				serve(server);
			}
			return;
		}
		if( args.length != 0 ) {
			System.err.println("usage: FshAdapterMain [--listen [address:]port]");
			System.exit(2);
		}
		// Standard output carries the protocol: nothing else may write to it
		java.io.PrintStream protocolOut = System.out;
		System.setOut(System.err);
		FshBackend backend = new FshBackend();
		LoupeConnection c = LoupeConnection.serve(e -> handler(e, backend), System.in, protocolOut);
		c.closed().join();
		backend.connectionClosed();
	}
}
