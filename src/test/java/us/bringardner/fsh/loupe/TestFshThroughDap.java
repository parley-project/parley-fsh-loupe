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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.dap.DapConnection;
import us.bringardner.parley.dap.json.Json;
import us.bringardner.parley.dap.protocol.ConfigurationDoneArguments;
import us.bringardner.parley.dap.protocol.ContinueArguments;
import us.bringardner.parley.dap.protocol.EvaluateArguments;
import us.bringardner.parley.dap.protocol.ExitedEventBody;
import us.bringardner.parley.dap.protocol.IDebugAdapter;
import us.bringardner.parley.dap.protocol.IDebugClient;
import us.bringardner.parley.dap.protocol.InitializeRequestArguments;
import us.bringardner.parley.dap.protocol.LaunchRequestArguments;
import us.bringardner.parley.dap.protocol.OutputEventBody;
import us.bringardner.parley.dap.protocol.ScopesArguments;
import us.bringardner.parley.dap.protocol.SetBreakpointsArguments;
import us.bringardner.parley.dap.protocol.StackFrame;
import us.bringardner.parley.dap.protocol.StackTraceArguments;
import us.bringardner.parley.dap.protocol.StoppedEventBody;
import us.bringardner.parley.dap.protocol.Variable;
import us.bringardner.parley.dap.protocol.VariablesArguments;
import us.bringardner.parley.loupe.LoupeConnection;
import us.bringardner.parley.loupe.dap.DapBridge;

/** An editor's DAP client debugging an fsh script: the bridge and the adapter, nothing faked. */
public class TestFshThroughDap {

	private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
	private final BlockingQueue<String> outputs = new LinkedBlockingQueue<>();

	private static <T> T get(CompletableFuture<T> f) throws Exception {
		return f.get(20, TimeUnit.SECONDS);
	}

	private String event(String prefix) throws Exception {
		for(;;) {
			String e = events.poll(20, TimeUnit.SECONDS);
			assertTrue(e != null, "no "+prefix);
			if( e.startsWith(prefix)) {
				return e;
			}
		}
	}

	@Test
	public void anEditorSessionFromLaunchToTheEnd() throws Exception {
		try (ServerSocket loupeServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
				ServerSocket dapServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			Socket bridgeToLoupe = new Socket(InetAddress.getLoopbackAddress(), loupeServer.getLocalPort());
			Socket loupe = loupeServer.accept();
			LoupeConnection adapter = LoupeConnection.serve(FshAdapterMain::handler, loupe.getInputStream(), loupe.getOutputStream());
			Socket clientToBridge = new Socket(InetAddress.getLoopbackAddress(), dapServer.getLocalPort());
			Socket bridgeSide = dapServer.accept();
			DapConnection bridge = DapBridge.open(bridgeSide.getInputStream(), bridgeSide.getOutputStream(),
					bridgeToLoupe.getInputStream(), bridgeToLoupe.getOutputStream());
			DapConnection client = DapConnection.client(new IDebugClient() {
				@Override
				public void initialized() {
					events.add("initialized");
				}

				@Override
				public void stopped(StoppedEventBody b) {
					events.add("stopped "+b.getReason());
				}

				@Override
				public void output(OutputEventBody b) {
					outputs.add(b.getCategory()+" "+b.getOutput());
				}

				@Override
				public void exited(ExitedEventBody b) {
					events.add("exited "+b.getExitCode());
				}

				@Override
				public void terminated(us.bringardner.parley.dap.protocol.TerminatedEventBody b) {
					events.add("terminated");
				}
			}, clientToBridge.getInputStream(), clientToBridge.getOutputStream());
			try {
				IDebugAdapter dap = client.getAdapter();
				get(dap.initialize(new InitializeRequestArguments().setAdapterID("fsh")));
				get(dap.launch(LaunchRequestArguments.fromJson(Json.parse(
						"{\"source\":"+Json.write(TestFshBackend.SCRIPT)+",\"name\":\"demo.fsh\"}"))));
				assertEquals("initialized", event("initialized"));
				get(dap.setBreakpoints(SetBreakpointsArguments.fromJson(Json.parse(
						"{\"source\":{\"path\":\"demo.fsh\"},\"breakpoints\":[{\"line\":10}]}"))));
				get(dap.configurationDone(new ConfigurationDoneArguments()));

				assertEquals("stopped breakpoint", event("stopped"));
				StackFrame frame = get(dap.stackTrace(StackTraceArguments.fromJson(Json.parse("{\"threadId\":1}")))).getStackFrames().get(0);
				assertEquals(10, frame.getLine());
				assertEquals("main", frame.getName());
				int vars = get(dap.scopes(new ScopesArguments().setFrameId(frame.getId()))).getScopes().get(0).getVariablesReference();
				List<Variable> list = get(dap.variables(new VariablesArguments().setVariablesReference(vars))).getVariables();
				assertTrue(list.stream().anyMatch(v -> v.getName().equals("count") && v.getValue().equals("\"6\"")), list.toString());
				assertEquals("60", get(dap.evaluate(new EvaluateArguments().setExpression("$((count * 10))")
						.setFrameId(frame.getId()).setContext("repl"))).getResult().replace("\"", ""));

				get(dap.continue_(new ContinueArguments().setThreadId(1)));
				assertEquals("exited 0", event("exited"));
				assertEquals("terminated", event("terminated"));
				StringBuilder all = new StringBuilder();
				String o;
				while((o = outputs.poll(200, TimeUnit.MILLISECONDS)) != null) {
					all.append(o);
				}
				assertTrue(all.toString().contains("hello world"), all.toString());
				assertTrue(all.toString().contains("done 6"), all.toString());
			} finally {
				client.close();
				bridge.close();
				adapter.close();
			}
		}
	}
}
