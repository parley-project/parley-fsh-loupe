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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.fsh.Console;
import us.bringardner.parley.loupe.LoupeConnection;
import us.bringardner.parley.loupe.LoupeEndpoint;
import us.bringardner.parley.loupe.LoupeException;
import us.bringardner.parley.loupe.protocol.Hello;
import us.bringardner.parley.loupe.protocol.Loupe;

/**
 * fsh --wait-for-debugger: the shell is started as its command line starts it
 * (Console.execute), waits, and a raw Loupe client attaches the way an IDE would.
 */
public class TestAttach {

	static final String SCRIPT = String.join("\n",
			"count=0",							// 1
			"for i in 1 2 3; do",				// 2
			"  count=$((count + i))",			// 3
			"done",								// 4
			"echo \"done $count $1\"",			// 5
			"exit 7",							// 6
			"");

	@TempDir
	Path dir;

	private Path script;
	private final ByteArrayOutputStream out = new ByteArrayOutputStream();
	private final ByteArrayOutputStream err = new ByteArrayOutputStream();
	private CompletableFuture<Integer> shell;
	private final List<Client> clients = new ArrayList<>();

	/** A debugger's side of a connection, and everything it opened. */
	private final class Client implements AutoCloseable {
		final Socket socket;
		final LoupeConnection connection;
		final BlockingQueue<Map<String,Object>> events = new LinkedBlockingQueue<>();
		Map<String,Object> welcome;

		Client(int port) throws Exception {
			socket = new Socket(InetAddress.getLoopbackAddress(), port);
			clients.add(this);
			connection = LoupeConnection.open(new LoupeEndpoint.Handler() {
				@Override
				public CompletableFuture<Object> request(String method, String target, Object params) {
					throw new LoupeException(LoupeException.UNKNOWN_METHOD, method);
				}

				@SuppressWarnings("unchecked")
				@Override
				public void event(String event, String target, Object params) {
					Map<String,Object> m = new LinkedHashMap<>((Map<String,Object>) params);
					m.put("event", event);
					events.add(m);
				}
			}, socket.getInputStream(), socket.getOutputStream());
			welcome = call("hello", null, new Hello().setProtocols(List.of("loupe/1")).setFeatures(List.of(Loupe.FEATURE_SNAPSHOT)).toJson());
		}

		@SuppressWarnings("unchecked")
		Map<String,Object> call(String method, String target, Object params) throws Exception {
			return (Map<String,Object>) connection.getEndpoint().request(method, target, params).get(20, TimeUnit.SECONDS);
		}

		Map<String,Object> next(String state) throws Exception {
			for(;;) {
				Map<String,Object> e = events.poll(20, TimeUnit.SECONDS);
				assertNotNull(e, "no targetState "+state+"; the shell wrote "+out+" and "+err);
				if( e.get("event").equals("targetState") && state.equals(e.get("state"))) {
					return e;
				}
			}
		}

		@Override
		public void close() throws Exception {
			connection.close();
			socket.close();
		}
	}

	@BeforeEach
	public void write() throws Exception {
		Console.exitJvm = false;
		script = dir.resolve("attach.fsh");
		Files.writeString(script, SCRIPT);
	}

	/** Closes every connection a test opened, and waits for its shell to end (so no thread or port is left). */
	@AfterEach
	public void close() throws Exception {
		for(Client c : clients) {
			c.close();
		}
		if( shell != null ) {
			shell.get(20, TimeUnit.SECONDS);
		}
	}

	/** Starts the shell as "fsh --wait-for-debugger 0 script arg" would, and gives the port it waits on. */
	private int start() throws Exception {
		shell = new CompletableFuture<>();
		Thread t = new Thread(() -> {
			try {
				Console c = new Console();
				c.setStdOut(new PrintStream(out, true));
				c.setStdErr(new PrintStream(err, true));
				c.setStdIn(new ByteArrayInputStream(new byte[0]));
				shell.complete(c.execute("--wait-for-debugger", "0", script.toString(), "arg"));
			} catch (Throwable e) {
				shell.completeExceptionally(e);
			}
		}, "test-shell");
		t.setDaemon(true);
		t.start();
		Pattern port = Pattern.compile("waiting for a debugger on port (\\d+)");
		long end = System.currentTimeMillis()+20000;
		for(;;) {
			Matcher m = port.matcher(new String(err.toByteArray(), StandardCharsets.UTF_8));
			if( m.find()) {
				return Integer.parseInt(m.group(1));
			}
			assertFalse(shell.isDone(), "the shell ended without waiting: "+err);
			assertTrue(System.currentTimeMillis() < end, "the shell never said where it waits: "+err);
			Thread.sleep(20);
		}
	}

	private static int line(Map<String,Object> paused) {
		@SuppressWarnings("unchecked")
		Map<String,Object> snapshot = (Map<String,Object>) paused.get("snapshot");
		@SuppressWarnings("unchecked")
		List<Map<String,Object>> frames = (List<Map<String,Object>>) snapshot.get("frames");
		return ((Number) frames.get(0).get("line")).intValue();
	}

	@SuppressWarnings("unchecked")
	private static String path(Map<String,Object> paused) {
		Map<String,Object> snapshot = (Map<String,Object>) paused.get("snapshot");
		List<Map<String,Object>> frames = (List<Map<String,Object>>) snapshot.get("frames");
		return (String) ((Map<String,Object>) frames.get(0).get("source")).get("path");
	}

	@Test
	public void theScriptWaitsThenRunsUnderTheDebugger() throws Exception {
		int port = start();
		// it waits: nothing has run
		Thread.sleep(200);
		assertEquals("", out.toString());
		assertFalse(shell.isDone());

		Client c = new Client(port);
		assertNotNull(c.welcome.get("attachSchema"), "a waiting script's adapter can attach");
		assertTrue(((List<?>) c.welcome.get("methods")).contains("attach"));
		// it is not an adapter that launches
		ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
				() -> c.call("launch", null, Map.of("target", "t0", "args", Map.of("source", "echo no"))));
		assertEquals(LoupeException.NOT_SUPPORTED, ((LoupeException) e.getCause()).getCode());

		Map<String,Object> r = c.call("attach", null, Map.of("target", "t1", "startPaused", true, "args", Map.of()));
		assertEquals("paused", r.get("state"));
		Map<String,Object> entry = c.next("paused");
		assertEquals("entry", entry.get("reason"));
		assertEquals(1, line(entry));
		assertEquals(script.toAbsolutePath().normalize().toString(), path(entry));

		// the script's text, for a debugger that has no copy of the file
		Map<String,Object> source = c.call(FshBackend.SOURCE, null, Map.of("path", path(entry)));
		assertEquals(SCRIPT, source.get("text"));

		// a step from the entry stop runs the first command and stops at the next
		c.call("step", "t1", Map.of("kind", "over"));
		Map<String,Object> stepped = c.next("paused");
		assertEquals("step", stepped.get("reason"));
		assertEquals(2, line(stepped));

		c.call("addBreakpoint", "t1", Map.of("id", "b1", "where", Map.of("source", Map.of("path", path(entry)), "line", 5)));
		c.call("resume", "t1", null);
		Map<String,Object> hit = c.next("paused");
		assertEquals("breakpoint", hit.get("reason"));
		assertEquals(5, line(hit));
		@SuppressWarnings("unchecked")
		Map<String,Object> value = (Map<String,Object>) c.call("evaluate", "t1", Map.of("expression", "$count-$1")).get("value");
		assertEquals("6-arg", value.get("v"));
		assertEquals("", out.toString(), "nothing is written before line 5 runs");

		c.call("resume", "t1", null);
		Map<String,Object> end = c.next("terminated");
		assertEquals("exit", end.get("reason"));
		assertEquals(7, ((Number) end.get("exitCode")).intValue());
		assertEquals(7, shell.get(20, TimeUnit.SECONDS).intValue());
		// the script's output is the shell's own, not the debugger's
		assertEquals("done 6 arg\n", out.toString());
		// the debugger's connection is closed when the script has ended
		c.connection.closed().get(20, TimeUnit.SECONDS);
	}

	@Test
	public void aDebuggerThatLeavesBeforeAttachingIsForgotten() throws Exception {
		int port = start();
		try (Client first = new Client(port)) {
			assertNotNull(first.welcome);
		}
		Thread.sleep(200);
		assertFalse(shell.isDone(), "the script still waits");
		assertEquals("", out.toString());

		Client c = new Client(port);
		// not paused at the start: it runs to the end
		Map<String,Object> r = c.call("attach", null, Map.of("target", "t1", "args", Map.of()));
		assertEquals("t1", r.get("target"));
		assertEquals(7, ((Number) c.next("terminated").get("exitCode")).intValue());
		assertEquals(7, shell.get(20, TimeUnit.SECONDS).intValue());
	}

	@Test
	public void theScriptRunsOnWhenItsDebuggerGoes() throws Exception {
		int port = start();
		Client c = new Client(port);
		c.call("attach", null, Map.of("target", "t1", "startPaused", true, "args", Map.of()));
		c.next("paused");
		c.call("addBreakpoint", "t1", Map.of("id", "b1", "where", Map.of("source", Map.of("path", script.toString()), "line", 5)));
		// stopped at the first line, with a breakpoint ahead: the debugger goes
		c.close();
		assertEquals(7, shell.get(20, TimeUnit.SECONDS).intValue());
		assertEquals("done 6 arg\n", out.toString());
	}

	@Test
	public void terminateEndsTheScript() throws Exception {
		int port = start();
		Client c = new Client(port);
		c.call("attach", null, Map.of("target", "t1", "startPaused", true, "args", Map.of()));
		c.next("paused");
		c.call("terminate", "t1", null);
		c.next("terminated");
		shell.get(20, TimeUnit.SECONDS);
		assertEquals("", out.toString(), "no command ran");
	}

	/** An adapter that launches scripts doesn't attach. */
	@Test
	public void theAdapterThatLaunchesHasNoAttach() throws Exception {
		try (java.net.ServerSocket server = FshAdapterMain.open("0")) {
			CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
				try {
					FshAdapterMain.serve(server);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			try (Client c = new Client(server.getLocalPort())) {
				assertNull(c.welcome.get("attachSchema"));
				assertFalse(((List<?>) c.welcome.get("methods")).contains("attach"));
				// its fsh/source reads a file where the adapter runs
				assertEquals(SCRIPT, c.call(FshBackend.SOURCE, null, Map.of("path", script.toString())).get("text"));
				ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
						() -> c.call(FshBackend.SOURCE, null, Map.of("path", dir.resolve("none.fsh").toString())));
				assertEquals(LoupeException.FAILED, ((LoupeException) e.getCause()).getCode());
			}
			server.close();
			served.get(20, TimeUnit.SECONDS);
		}
	}

	/** A script launched over a connection is ended when the connection closes: it doesn't run on unseen. */
	@Test
	public void aLaunchedScriptEndsWithItsConnection() throws Exception {
		Path mark = dir.resolve("mark");
		try (java.net.ServerSocket server = FshAdapterMain.open("0")) {
			CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
				try {
					FshAdapterMain.serve(server);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			try (Client c = new Client(server.getLocalPort())) {
				c.call("launch", null, Map.of("target", "t1", "startPaused", true,
						"args", Map.of("source", "echo one\necho made > '"+mark+"'\n", "name", "x.fsh")));
				c.next("paused");
			}
			// the adapter is free for the next debugger only once the script has ended
			try (Client again = new Client(server.getLocalPort())) {
				assertNotNull(again.welcome);
			}
			assertFalse(Files.exists(mark), "the script went on after its debugger left");
			server.close();
			served.get(20, TimeUnit.SECONDS);
		}
	}
}
