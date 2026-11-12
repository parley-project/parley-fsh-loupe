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

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import us.bringardner.parley.dap.DapConnection;
import us.bringardner.parley.dap.DapFraming;
import us.bringardner.parley.dap.json.Json;
import us.bringardner.parley.loupe.LoupeConnection;
import us.bringardner.parley.loupe.dap.DapBridge;

/**
 * A debugger connected to the adapter on a socket, speaking Loupe or the Debug Adapter
 * Protocol: the adapter tells which from the debugger's first message (both frame their
 * messages the same way; Loupe's first is a {@code hello} request with a {@code method}, DAP's
 * an {@code initialize} request with a {@code command}), so one port serves both and nothing
 * has to be said when the adapter is started. A DAP debugger is answered through
 * parley-loupe's bridge ({@link DapBridge}), in front of the same adapter.
 */
public final class DebuggerConnection implements Closeable {

	/** The protocols a debugger may speak. */
	public enum Protocol { LOUPE, DAP }

	private final Protocol protocol;
	private final CompletableFuture<Void> closed = new CompletableFuture<>();
	private final Closeable[] parts;

	private DebuggerConnection(Protocol protocol, Closeable... parts) {
		this.protocol = protocol;
		this.parts = parts;
	}

	/**
	 * Waits for the debugger's first message, and serves it with backend in the protocol it speaks.
	 *
	 * @return the connection, or null if the debugger closed without sending anything
	 * @throws IOException if what it sent is not a message of either protocol
	 */
	public static DebuggerConnection serve(FshBackend backend, InputStream in, OutputStream out) throws IOException {
		String first = DapFraming.read(in);
		if( first == null ) {
			return null;
		}
		boolean dap = false;
		try {
			Object json = Json.parse(first);
			dap = json instanceof Map && !((Map<?,?>) json).containsKey("method") && ((Map<?,?>) json).containsKey("command");
		} catch (Exception e) {
			// (not JSON: the Loupe connection says what is wrong with it)
		}
		// (the message read is given back in front of the rest)
		InputStream all = new SequenceInputStream(new ByteArrayInputStream(DapFraming.frame(first)), in);
		if( !dap ) {
			LoupeConnection c = LoupeConnection.serve(e -> FshAdapterMain.handler(e, backend), all, out);
			DebuggerConnection ret = new DebuggerConnection(Protocol.LOUPE, c);
			c.closed().thenRun(() -> ret.closed.complete(null));
			return ret;
		}
		// DAP: the bridge between the debugger and the adapter, which talk Loupe over a loopback connection
		Socket bridgeSide = null;
		Socket adapterSide = null;
		try (ServerSocket pair = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			bridgeSide = new Socket(InetAddress.getLoopbackAddress(), pair.getLocalPort());
			adapterSide = pair.accept();
			LoupeConnection adapter = LoupeConnection.serve(e -> FshAdapterMain.handler(e, backend), adapterSide.getInputStream(), adapterSide.getOutputStream());
			DapConnection bridge = DapBridge.open(all, out, bridgeSide.getInputStream(), bridgeSide.getOutputStream());
			DebuggerConnection ret = new DebuggerConnection(Protocol.DAP, bridge, adapter, bridgeSide, adapterSide);
			// (the debugger went, or it disconnected and the bridge closed the adapter's side)
			bridge.closed().thenRun(() -> ret.closed.complete(null));
			adapter.closed().thenRun(() -> ret.closed.complete(null));
			return ret;
		} catch (IOException | RuntimeException e) {
			for(Socket s : new Socket[] {bridgeSide, adapterSide}) {
				if( s != null ) {
					try {
						s.close();
					} catch (IOException x) {
						// closing
					}
				}
			}
			throw e;
		}
	}

	/** The protocol the debugger speaks. */
	public Protocol getProtocol() {
		return protocol;
	}

	/** Completes when the debugger has gone (or, for DAP, disconnected). */
	public CompletableFuture<Void> closed() {
		return closed;
	}

	public boolean isClosed() {
		return closed.isDone();
	}

	/** Closes the connection and everything opened for it (not the debugger's own streams: their owner closes those). */
	@Override
	public void close() {
		for(Closeable c : parts) {
			try {
				c.close();
			} catch (IOException e) {
				// closing
			}
		}
		closed.complete(null);
	}
}
