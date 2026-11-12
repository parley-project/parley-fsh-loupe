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
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import us.bringardner.fsh.Console;
import us.bringardner.fsh.spi.DebuggerProvider;

/**
 * What {@code fsh --wait-for-debugger [address:]port script} waits with: it listens there (as
 * {@link FshAdapterMain}'s {@code --listen} does, with the same warning for an address other
 * machines can reach) until a debugger connects (speaking Loupe or DAP, see
 * {@link DebuggerConnection}) and sends {@code attach}; the script
 * then runs under that debugger, in the shell that was started. A connection that closes
 * before it attaches is forgotten, and the wait goes on. Once a debugger has attached the
 * port is closed: when that debugger goes, the script runs on without one.
 */
public class FshDebuggerProvider implements DebuggerProvider {

	@Override
	public String name() {
		return "loupe";
	}

	@Override
	public Session waitForDebugger(Console console, String where, String script, String code) throws IOException {
		try (ServerSocket server = FshAdapterMain.open(where)) {
			return waitForDebugger(console, server, script, code);
		} catch (java.net.UnknownHostException e) {
			throw new IllegalArgumentException("unknown address "+e.getMessage());
		}
	}

	/** As waitForDebugger with where, on a socket the caller opened (and closes). */
	public Session waitForDebugger(Console console, ServerSocket server, String script, String code) throws IOException {
		String warning = FshAdapterMain.warning(server);
		if( warning != null ) {
			console.getStdErr().println(warning);
		}
		console.getStdErr().println("fsh: "+script+" is waiting for a debugger on port "+server.getLocalPort());
		console.getStdErr().flush();
		for(;;) {
			Socket socket = server.accept();
			FshBackend backend = new FshBackend(console, script, code);
			DebuggerConnection c;
			try {
				// (Loupe or DAP: told from the debugger's first message)
				c = DebuggerConnection.serve(backend, socket.getInputStream(), socket.getOutputStream());
			} catch (IOException | RuntimeException e) {
				// not a debugger: the script still waits
				close(socket);
				continue;
			}
			if( c == null ) {
				close(socket);
				continue;
			}
			CompletableFuture.anyOf(backend.attached(), c.closed()).join();
			if( !backend.attached().isDone()) {
				// it left without attaching: the script still waits
				c.close();
				close(socket);
				continue;
			}
			AtomicBoolean over = new AtomicBoolean();
			c.closed().thenRun(() -> {
				backend.connectionClosed();
				close(socket);
			});
			return status -> {
				if( over.compareAndSet(false, true)) {
					backend.ended(status);
					finish(c, socket);
				}
			};
		}
	}

	/** How long the end of a script waits for its debugger to close the connection. */
	static final int CLOSE_MS = 2000;

	/**
	 * Ends the connection so the debugger gets everything that was sent: closing a socket that
	 * still has something to read can throw away what the other end hasn't read yet (the end of
	 * the script, the answer to its last request). So: a moment for an answer on its way, then
	 * this end says it has no more to send, and waits (not for long) for the debugger to close.
	 */
	private static void finish(DebuggerConnection c, Socket socket) {
		// (the script's thread may have been interrupted by a terminate: the waits below are still wanted)
		boolean interrupted = Thread.interrupted();
		try {
			if( !c.isClosed()) {
				Thread.sleep(100);
				socket.shutdownOutput();
				c.closed().get(CLOSE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
			}
		} catch (InterruptedException e) {
			interrupted = true;
		} catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
			// closed already, or the debugger is slow to: closed below
		}
		c.close();
		close(socket);
		if( interrupted ) {
			Thread.currentThread().interrupt();
		}
	}

	private static void close(Socket socket) {
		try {
			socket.close();
		} catch (IOException e) {
			// closed
		}
	}
}
