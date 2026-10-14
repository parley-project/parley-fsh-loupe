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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.loupe.LoupeConnection;
import us.bringardner.parley.loupe.LoupeEndpoint;
import us.bringardner.parley.loupe.LoupeException;
import us.bringardner.parley.loupe.protocol.EvaluateResult;
import us.bringardner.parley.loupe.protocol.FramesResult;
import us.bringardner.parley.loupe.protocol.Hello;
import us.bringardner.parley.loupe.protocol.Loupe;
import us.bringardner.parley.loupe.protocol.ScopesResult;
import us.bringardner.parley.loupe.protocol.Value;
import us.bringardner.parley.loupe.protocol.VariablesResult;

/** An fsh script debugged through the adapter, with a raw Loupe client the way an IDE would. */
public class TestFshBackend {

	static final String SCRIPT = String.join("\n",
			"greet() {",						// 1
			"  local who=$1",					// 2
			"  echo \"hello $who\"",			// 3
			"}",								// 4
			"count=0",							// 5
			"for i in 1 2 3; do",				// 6
			"  count=$((count + i))",			// 7
			"done",								// 8
			"arr=(a b c)",						// 9
			"greet world",						// 10
			"echo \"done $count\"",				// 11
			"");

	private ServerSocket server;
	private LoupeConnection adapter;
	private LoupeConnection client;
	private final BlockingQueue<Map<String,Object>> events = new LinkedBlockingQueue<>();
	private final List<String> output = new ArrayList<>();

	@BeforeEach
	public void connect() throws Exception {
		// (sockets: a PipedInputStream fails with "Write end dead" once the thread that wrote to it, a
		// script's, has ended)
		server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		Socket c = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
		Socket a = server.accept();
		adapter = LoupeConnection.serve(FshAdapterMain::handler, a.getInputStream(), a.getOutputStream());
		client = LoupeConnection.open(new LoupeEndpoint.Handler() {
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
		}, c.getInputStream(), c.getOutputStream());
		call("hello", null, new Hello().setProtocols(List.of("loupe/1")).setFeatures(List.of(Loupe.FEATURE_SNAPSHOT)).toJson());
	}

	@AfterEach
	public void disconnect() throws Exception {
		client.close();
		adapter.close();
		server.close();
	}

	@SuppressWarnings("unchecked")
	private Map<String,Object> call(String method, String target, Object params) throws Exception {
		return (Map<String,Object>) client.getEndpoint().request(method, target, params).get(20, TimeUnit.SECONDS);
	}

	private Map<String,Object> t1(String method, Object params) throws Exception {
		return call(method, "t1", params);
	}

	private Map<String,Object> next(String event, String state) throws Exception {
		for(;;) {
			Map<String,Object> e = events.poll(20, TimeUnit.SECONDS);
			assertTrue(e != null, "no "+event+" "+state+"; output so far "+output);
			if( e.get("event").equals("output")) {
				output.add(e.get("category")+": "+e.get("text"));
			}
			if( e.get("event").equals(event) && (state == null || state.equals(e.get("state")))) {
				return e;
			}
		}
	}

	private void launch(boolean startPaused) throws Exception {
		call("launch", null, Map.of("target", "t1", "startPaused", startPaused,
				"args", Map.of("source", SCRIPT, "name", "demo.fsh")));
	}

	private void breakpoint(String id, int line, String condition) throws Exception {
		Map<String,Object> p = new LinkedHashMap<>();
		p.put("id", id);
		p.put("where", Map.of("source", Map.of("path", "demo.fsh"), "line", line));
		if( condition != null ) {
			p.put("condition", condition);
		}
		t1("addBreakpoint", p);
		next("breakpointStatus", null);
	}

	private FramesResult frames() throws Exception {
		return FramesResult.fromJson(t1("frames", Map.of("thread", 1, "start", 0)));
	}

	private Value variable(String name) throws Exception {
		String frame = frames().getFrames().get(0).getId();
		VariablesResult v = VariablesResult.fromJson(t1("locals", Map.of("frame", frame, "start", 0)));
		return v.getVariables().stream().filter(x -> x.getName().equals(name)).findFirst().get().getValue();
	}

	@Test
	public void rejectsALaunchWithNoScript() throws Exception {
		ExecutionException e = assertThrows(ExecutionException.class,
				()->call("launch", null, Map.of("target", "t1", "args", Map.of("name", "x"))));
		assertTrue(e.getCause().getMessage().contains("script or a source"), e.getCause().getMessage());
	}

	@Test
	public void debugsAScript() throws Exception {
		launch(true);
		Map<String,Object> entry = next("targetState", "paused");
		assertEquals("entry", entry.get("reason"));
		assertEquals(1, frames().getTotal());

		breakpoint("loop", 7, "[ $i -eq 2 ]");
		breakpoint("fn", 3, null);
		t1("resume", Map.of());
		next("targetState", "running");
		Map<String,Object> stop = next("targetState", "paused");
		assertEquals("breakpoint", stop.get("reason"));
		assertEquals(List.of("loop"), stop.get("breakpoints"));
		assertEquals(7, frames().getFrames().get(0).getLine());
		assertEquals("demo.fsh", frames().getFrames().get(0).getSource().getName());

		// the first pass has added 1; this one hasn't run yet
		assertEquals("1", variable("count").getV());
		assertEquals("2", variable("i").getV());
		String frame = frames().getFrames().get(0).getId();
		assertEquals("1", EvaluateResult.fromJson(t1("evaluate", Map.of("expression", "count", "frame", frame))).getValue().getV());
		assertEquals("30", EvaluateResult.fromJson(t1("evaluate", Map.of("expression", "$((count * 30))", "frame", frame))).getValue().getV());
		ExecutionException e = assertThrows(ExecutionException.class,
				()->t1("evaluate", Map.of("expression", "nothing_here", "frame", frame)));
		assertTrue(e.getCause().getMessage().contains("not set"), e.getCause().getMessage());
		Map<String,Object> c = t1("completions", Map.of("text", "co", "offset", 2, "frame", frame));
		assertEquals("count", ((Map<?,?>) ((List<?>) c.get("items")).get(0)).get("label"));
		ScopesResult scopes = ScopesResult.fromJson(t1("scopes", Map.of("frame", frame)));
		assertEquals(List.of("variables", "arguments", "environment"),
				List.of(scopes.getScopes().get(0).getName(), scopes.getScopes().get(1).getName(), scopes.getScopes().get(2).getName()));

		// change a variable: the loop adds 2 to it
		t1("setVariable", Map.of("id", scopes.getScopes().get(0).getId(), "name", "count", "value", "100"));
		t1("step", Map.of("kind", "over"));
		next("targetState", "paused");
		assertEquals("102", EvaluateResult.fromJson(t1("evaluate", Map.of("expression", "count",
				"frame", frames().getFrames().get(0).getId()))).getValue().getV());

		// on to the call: a second frame, in the function
		t1("removeBreakpoint", Map.of("id", "loop"));
		t1("resume", Map.of());
		stop = next("targetState", "paused");
		assertEquals(List.of("fn"), stop.get("breakpoints"));
		FramesResult f = frames();
		assertEquals(2, f.getTotal());
		assertEquals("greet", f.getFrames().get(0).getName());
		assertEquals(3, f.getFrames().get(0).getLine());
		assertEquals("main", f.getFrames().get(1).getName());
		assertEquals(10, f.getFrames().get(1).getLine());
		assertEquals("world", variable("who").getV());

		// the array is a value with parts
		// (the top frame is greet, but fsh has one set of variables)
		Value arr = variable("arr");
		assertEquals(Value.ARRAY, arr.getT());
		assertEquals(3, arr.getSize());
		VariablesResult items = VariablesResult.fromJson(t1("children", Map.of("id", arr.getId())));
		assertEquals("b", items.getVariables().get(1).getValue().getV());

		// out of the function: back in main, at the next command
		t1("step", Map.of("kind", "out"));
		next("targetState", "paused");
		f = frames();
		assertEquals(1, f.getTotal());
		assertEquals(11, f.getFrames().get(0).getLine());

		t1("resume", Map.of());
		Map<String,Object> end = next("targetState", "terminated");
		assertEquals(0L, end.get("exitCode"));
		assertTrue(output.contains("stdout: hello world\n"), output.toString());
		assertTrue(output.contains("stdout: done 105\n"), output.toString());
	}

	@Test
	public void runsToTheEndWhenNotStartedPaused() throws Exception {
		launch(false);
		Map<String,Object> end = next("targetState", "terminated");
		assertEquals(0L, end.get("exitCode"));
		assertTrue(output.contains("stdout: done 6\n"), output.toString());
	}

	@Test
	public void terminateEndsAStoppedScript() throws Exception {
		launch(true);
		next("targetState", "paused");
		t1("terminate", Map.of());
		Map<String,Object> end = next("targetState", "terminated");
		assertEquals("kill", end.get("reason"));
	}

	@Test
	public void logMessagesAndHitCounts() throws Exception {
		launch(true);
		next("targetState", "paused");
		Map<String,Object> p = new LinkedHashMap<>();
		p.put("id", "log");
		p.put("where", Map.of("source", Map.of("path", "demo.fsh"), "line", 7));
		p.put("logMessage", "i is $i");
		t1("addBreakpoint", p);
		next("breakpointStatus", null);
		t1("resume", Map.of());
		next("targetState", "terminated");
		assertEquals(1, output.stream().filter(o -> o.equals("console: i is 1\n")).count(), output.toString());
		assertEquals(1, output.stream().filter(o -> o.equals("console: i is 3\n")).count(), output.toString());
	}
}
