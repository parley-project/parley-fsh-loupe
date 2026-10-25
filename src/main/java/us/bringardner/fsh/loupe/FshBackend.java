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

import java.awt.Point;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import us.bringardner.fsh.Argument;
import us.bringardner.fsh.Console;
import us.bringardner.fsh.ConsoleSignal;
import us.bringardner.fsh.DebugContext;
import us.bringardner.fsh.FshList;
import us.bringardner.fsh.ShellContext;
import us.bringardner.fsh.ShellContext.LoopControl;
import us.bringardner.fsh.exec.Executor;
import us.bringardner.fsh.job.ForgroundJob;
import us.bringardner.fsh.signal.LoopControlException;
import us.bringardner.fsh.syntax.Ast;
import us.bringardner.fsh.syntax.Parser;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.loupe.BreakpointTable;
import us.bringardner.parley.loupe.LoupeException;
import us.bringardner.parley.loupe.Target;
import us.bringardner.parley.loupe.TargetBackend;
import us.bringardner.parley.loupe.protocol.BreakpointStatus;
import us.bringardner.parley.loupe.protocol.ChildrenParams;
import us.bringardner.parley.loupe.protocol.CompletionItem;
import us.bringardner.parley.loupe.protocol.CompletionsParams;
import us.bringardner.parley.loupe.protocol.CompletionsResult;
import us.bringardner.parley.loupe.protocol.EvaluateContext;
import us.bringardner.parley.loupe.protocol.EvaluateParams;
import us.bringardner.parley.loupe.protocol.EvaluateResult;
import us.bringardner.parley.loupe.protocol.Frame;
import us.bringardner.parley.loupe.protocol.FramesParams;
import us.bringardner.parley.loupe.protocol.FramesResult;
import us.bringardner.parley.loupe.protocol.LocalsParams;
import us.bringardner.parley.loupe.protocol.Location;
import us.bringardner.parley.loupe.protocol.Loupe;
import us.bringardner.parley.loupe.protocol.ObjectId;
import us.bringardner.parley.loupe.protocol.OutputEvent;
import us.bringardner.parley.loupe.protocol.Scope;
import us.bringardner.parley.loupe.protocol.ScopesParams;
import us.bringardner.parley.loupe.protocol.ScopesResult;
import us.bringardner.parley.loupe.protocol.SetVariableParams;
import us.bringardner.parley.loupe.protocol.SetVariableResult;
import us.bringardner.parley.loupe.protocol.Snapshot;
import us.bringardner.parley.loupe.protocol.Source;
import us.bringardner.parley.loupe.protocol.StepKind;
import us.bringardner.parley.loupe.protocol.StepParams;
import us.bringardner.parley.loupe.protocol.ThreadInfo;
import us.bringardner.parley.loupe.protocol.Value;
import us.bringardner.parley.loupe.protocol.Variable;
import us.bringardner.parley.loupe.protocol.VariablesResult;

/**
 * Debugs an fsh script for a Loupe client, through fsh's {@link DebugContext}.
 * <p>
 * fsh calls the context on the script's thread before each command. This backend stops
 * there (waiting on a lock) when a breakpoint, a step, a pause request or the entry stop says
 * so, and answers the client's requests from that thread's {@link ShellContext} while it
 * waits. The stack is fsh's call frames ({@code FUNCNAME}, {@code BASH_SOURCE},
 * {@code BASH_LINENO}): functions and sourced files.
 * <p>
 * What it doesn't do (yet): stop in the threads of pipeline stages, background jobs and
 * subshells (only the script's own thread is debugged); give the script a standard input;
 * run a command in evaluate (it expands a word: {@code $x}, {@code ${a[1]}},
 * {@code $((n+1))}).
 * <p>
 * Launch arguments: {@code script} (a path, any parley-files file system) or {@code source}
 * (the text) with {@code name} (what to call it: $0), and {@code args} (the script's
 * arguments).
 */
public class FshBackend implements TargetBackend {

	static {
		// A script's exit ends the script, not the JVM (fsh's default is for its own command line)
		Console.exitJvm = false;
	}

	/** A JSON Schema for the launch arguments. */
	public static Object launchSchema() {
		Map<String,Object> props = new LinkedHashMap<>();
		props.put("script", Map.of("type", "string", "description", "Path of the script to run"));
		props.put("source", Map.of("type", "string", "description", "The script's text, instead of a path"));
		props.put("name", Map.of("type", "string", "description", "$0: what to call the script when it is given as source"));
		props.put("args", Map.of("type", "array", "items", Map.of("type", "string"), "description", "The script's arguments"));
		Map<String,Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", props);
		schema.put("additionalProperties", false);
		return schema;
	}

	private static final int MAX_TEXT = 10000;
	private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	private final Object lock = new Object();
	// guarded by lock
	private boolean holding;
	private boolean killed;
	private boolean pauseRequested;
	private String stepKind;
	private int stepDepth;
	private Ast.Node stepNode;
	private boolean entryDone;
	private final Map<String,Object> handles = new LinkedHashMap<>();

	// the script, set once by launch
	private volatile Target target;
	private volatile Console console;
	private volatile String mainPath;
	private volatile String[] mainLines;
	private volatile ForgroundJob job;
	private volatile Thread scriptThread;
	private volatile boolean inCondition;
	private volatile CompletableFuture<Void> launched;
	// where the script is, while it is stopped (or about to run a command)
	private volatile ShellContext currentCtx;
	private volatile Ast.Node currentNode;

	@Override
	public boolean supports(String method) {
		switch(method) {
		case Loupe.RESUME:
		case Loupe.STEP:
		case Loupe.PAUSE:
		case Loupe.TERMINATE:
		case Loupe.THREADS:
		case Loupe.FRAMES:
		case Loupe.SCOPES:
		case Loupe.LOCALS:
		case Loupe.CHILDREN:
		case Loupe.EVALUATE:
		case Loupe.COMPLETIONS:
		case Loupe.SET_VARIABLE:
			return true;
		default:
			return false;
		}
	}

	// ---- launch

	@SuppressWarnings("unchecked")
	@Override
	public CompletableFuture<Void> launch(Target target, Object args) {
		if( this.target != null ) {
			throw new LoupeException(LoupeException.FAILED, "This adapter runs one script per connection");
		}
		Map<String,Object> a = args instanceof Map ? (Map<String,Object>) args : Map.of();
		String code;
		String name = (String) a.get("name");
		try {
			if( a.get("source") != null ) {
				code = (String) a.get("source");
				mainPath = name != null ? name : "script.fsh";
			} else if( a.get("script") != null ) {
				String path = (String) a.get("script");
				code = read(path);
				mainPath = absolute(path);
				if( name == null ) {
					name = path;
				}
			} else {
				throw new LoupeException(LoupeException.INVALID_PARAMS, "Give a script or a source");
			}
		} catch (IOException e) {
			throw new LoupeException(LoupeException.FAILED, "Can't read the script: "+e.getMessage());
		}
		if( name == null ) {
			name = mainPath;
		}
		mainLines = code.split("\r?\n", -1);
		List<String> scriptArgs = new ArrayList<>();
		Object list = a.get("args");
		if( list instanceof List ) {
			for(Object o : (List<Object>) list) {
				scriptArgs.add(String.valueOf(o));
			}
		}
		this.target = target;
		launched = new CompletableFuture<>();
		boolean startPaused = target.isStartPaused();
		synchronized (lock) {
			// (started paused: the first command is where it waits)
			entryDone = !startPaused;
		}

		String scriptName = name;
		Thread t = new Thread(null, () -> run(target, code, scriptName, scriptArgs), "fsh-script", 4*1024*1024);
		t.setDaemon(true);
		t.start();
		if( !startPaused ) {
			launched.complete(null);
		}
		return launched;
	}

	private static String absolute(String path) {
		try {
			return Paths.get(path).toAbsolutePath().normalize().toString();
		} catch (RuntimeException e) {
			return path;
		}
	}

	private static String read(String path) throws IOException {
		FileSource f = FileSourceFactory.getDefaultFactory().createFileSource(path);
		try (InputStream in = f.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private void run(Target target, String code, String name, List<String> args) {
		int exit = 0;
		PrintStream out = null;
		PrintStream err = null;
		try {
			Console c = new Console();
			console = c;
			c.setStdIn(new ByteArrayInputStream(new byte[0]));
			out = new PrintStream(new Chunks(target, OutputEvent.STDOUT), true, StandardCharsets.UTF_8);
			err = new PrintStream(new Chunks(target, OutputEvent.STDERR), true, StandardCharsets.UTF_8);
			c.setStdOut(out);
			c.setStdErr(err);
			FshList list = new FshList();
			list.add(new Argument(name));
			for(String s : args) {
				list.add(new Argument(s));
			}
			c.setPositionalParameters(true, list);
			Debug d = new Debug();
			d.setCurrentState(DebugContext.RunState.Running);
			c.setDebugContext(d);
			ShellContext sc = new ShellContext(c);
			ForgroundJob j = new ForgroundJob(sc, code);
			synchronized (lock) {
				if( killed ) {
					return;
				}
				job = j;
				j.start();
			}
			while(j.isAlive()) {
				try {
					j.join(0);
				} catch (InterruptedException e) {
					// cancel interrupts: the kill signal it sent ends the job
				}
			}
			exit = j.getExitCode();
		} catch (RuntimeException e) {
			exit = 1;
			target.output(OutputEvent.STDERR, "fsh: "+e+"\n");
		} finally {
			// a script that ended before the entry stop (nothing to run): the launch is done
			launched.complete(null);
			// (what the script wrote comes before the end)
			if( out != null ) {
				out.flush();
				err.flush();
			}
		}
		synchronized (lock) {
			if( killed ) {
				return;	// the handler ends the target
			}
		}
		try {
			target.terminated("exit", exit);
		} catch (LoupeException e) {
			// ended already
		}
	}

	/** Output of the script, sent as it's written (a line at a time, or when the stream is flushed). */
	private static final class Chunks extends OutputStream {
		private final Target target;
		private final String category;
		private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

		Chunks(Target target, String category) {
			this.target = target;
			this.category = category;
		}

		@Override
		public synchronized void write(int b) {
			buf.write(b);
			if( b == '\n') {
				flush();
			}
		}

		@Override
		public synchronized void write(byte[] b, int off, int len) {
			buf.write(b, off, len);
			if( len > 0 && b[off+len-1] == '\n') {
				flush();
			}
		}

		@Override
		public synchronized void flush() {
			if( buf.size() > 0 ) {
				String text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
				buf.reset();
				try {
					target.output(category, text);
				} catch (RuntimeException e) {
					// the connection is gone
				}
			}
		}
	}

	// ---- fsh's side: called on the script's thread

	private final class Debug extends DebugContext {

		@Override
		public synchronized void before(Ast.Node node, String source, ShellContext ctx) {
			if( inCondition || Thread.currentThread() != scriptThread && scriptThread != null ) {
				return;	// an evaluate's commands, or another thread's: not the script's
			}
			synchronized (lock) {
				if( killed ) {
					throw new LoopControlException(LoopControl.Break, 100000);
				}
			}
			currentNode = node;
			currentCtx = ctx;
		}

		@Override
		public boolean isBreakpoint(Point point, ShellContext ctx) {
			Thread me = Thread.currentThread();
			if( scriptThread == null ) {
				scriptThread = me;
			}
			if( me != scriptThread || inCondition ) {
				return false;
			}
			stopIfNeeded(point.x, ctx);
			return false;	// (fsh's own stop is not used: this backend waits itself)
		}
	}

	private void stopIfNeeded(int line, ShellContext ctx) {
		Target t = target;
		List<String> hit = new ArrayList<>();
		String reason = null;
		boolean entry = false;
		synchronized (lock) {
			if( !entryDone ) {
				entryDone = true;
				entry = true;
				holding = true;
			}
		}
		if( entry ) {
			announce(t, ctx, "entry", null);
			launched.complete(null);
			synchronized (lock) {
				waitWhileHolding();
			}
		}
		int depth = ctx.callFrames.size();
		String file = fileOf(ctx);
		BreakpointTable table = t.getBreakpoints();
		for(BreakpointTable.Entry e : table.list()) {
			Location w = e.getWhere();
			if( !BreakpointStatus.VERIFIED.equals(e.getStatus()) || w == null || w.getLine() == null || w.getLine() != line
					|| !samePath(w.getSource() == null ? null : w.getSource().getPath(), file)) {
				continue;
			}
			String cond = e.getParams().getCondition();
			if( cond != null && !cond.isBlank() && !conditionHolds(ctx, cond)) {
				continue;
			}
			if( !table.hit(e.getId())) {
				continue;
			}
			String log = e.getParams().getLogMessage();
			if( log != null ) {
				t.output(OutputEvent.CONSOLE, expand(ctx, log)+"\n");
				continue;
			}
			hit.add(e.getId());
		}
		synchronized (lock) {
			if( !hit.isEmpty()) {
				reason = "breakpoint";
			} else if( pauseRequested ) {
				reason = "pause";
			} else if( stepKind != null ) {
				switch(stepKind) {
				case StepKind.IN:
					// (the next command to start is a new one)
					reason = "step";
					break;
				case StepKind.OVER:
					// back in the frame it started in, and not inside the command it stepped over
					// (the next time round a loop is the same node, but not inside it)
					reason = depth < stepDepth || depth == stepDepth && !inside(ctx, stepNode) ? "step" : null;
					break;
				case StepKind.OUT:
					reason = depth < stepDepth ? "step" : null;
					break;
				default:
					break;
				}
			}
			if( reason == null ) {
				return;
			}
			holding = true;
			pauseRequested = false;
			stepKind = null;
		}
		announce(t, ctx, reason, hit);
		synchronized (lock) {
			waitWhileHolding();
		}
	}

	private static boolean inside(ShellContext ctx, Ast.Node node) {
		if( node == null ) {
			return false;
		}
		// (the first is the command about to run: the same node again is a new run of it)
		List<Ast.Node> stack = ctx.getNodeStack(Integer.MAX_VALUE);
		for(int i=1; i < stack.size(); i++) {
			if( stack.get(i) == node ) {
				return true;
			}
		}
		return false;
	}

	private void announce(Target t, ShellContext ctx, String reason, List<String> hit) {
		synchronized (lock) {
			handles.clear();
		}
		try {
			t.pausedWith(reason, () -> snapshot(t, ctx), hit == null || hit.isEmpty() ? null : hit);
		} catch (LoupeException e) {
			// the target was terminated under us
			synchronized (lock) {
				holding = false;
				killed = true;
				lock.notifyAll();
			}
			throw new LoopControlException(LoopControl.Break, 100000);
		}
	}

	// called with lock held
	private void waitWhileHolding() {
		while(holding && !killed) {
			try {
				lock.wait();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				killed = true;
			}
		}
		if( killed ) {
			throw new LoopControlException(LoopControl.Break, 100000);
		}
	}

	/** True if the command (such as [ $i -gt 3 ]) succeeds; an error stops, as the IDE's does. */
	private boolean conditionHolds(ShellContext ctx, String cond) {
		inCondition = true;
		try {
			return Executor.test(ctx, cond);
		} catch (IOException | RuntimeException e) {
			target.output(OutputEvent.CONSOLE, "Breakpoint condition '"+cond+"': "+e.getMessage()+"\n");
			return true;
		} finally {
			inCondition = false;
		}
	}

	private String expand(ShellContext ctx, String text) {
		try {
			return Executor.expanderFor(ctx).string(Parser.fragment(text, Parser.Fragment.WORD));
		} catch (RuntimeException e) {
			return text;
		}
	}

	// ---- files and lines

	/** The file the command running was read from. */
	private String fileOf(ShellContext ctx) {
		List<String[]> f = new ArrayList<>(ctx.callFrames);
		if( !f.isEmpty()) {
			String p = f.get(f.size()-1)[1];
			if( p != null && !p.isEmpty() && !p.equals("main")) {
				return absolute(p);
			}
		}
		return mainPath;
	}

	private static boolean samePath(String a, String b) {
		if( a == null || b == null ) {
			return false;
		}
		return a.equals(b) || absolute(a).equals(absolute(b));
	}

	@Override
	public void breakpointAdded(Target target, BreakpointTable.Entry bp) {
		Location w = bp.getWhere();
		String path = w.getSource().getPath();
		String[] lines;
		if( samePath(path, mainPath)) {
			lines = mainLines;
		} else {
			try {
				lines = read(path).split("\r?\n", -1);
			} catch (IOException | RuntimeException e) {
				target.setBreakpointStatus(bp.getId(), BreakpointStatus.REJECTED, null, "Can't read "+path);
				return;
			}
		}
		for(int l = w.getLine(); l <= lines.length; l++) {
			String text = lines[l-1].trim();
			if( !text.isEmpty() && !text.startsWith("#")) {
				target.setBreakpointStatus(bp.getId(), BreakpointStatus.VERIFIED,
						new Location().setSource(w.getSource()).setLine(l).setColumn(w.getColumn()), null);
				return;
			}
		}
		target.setBreakpointStatus(bp.getId(), BreakpointStatus.REJECTED, null, "There is no code at or after line "+w.getLine());
	}

	// ---- the stop: frames, scopes and values (the script's thread is waiting, so nothing changes)

	private Snapshot snapshot(Target t, ShellContext ctx) {
		List<Frame> frames = frames(t, ctx);
		return new Snapshot().setThreads(List.of(thread(t))).setThread(1).setFrames(frames).setTotalFrames(frames.size())
				.setLocals(variablesOf(t, shellVariables(ctx)));
	}

	private ThreadInfo thread(Target t) {
		return new ThreadInfo().setId(1).setName("main").setState(t.getState());
	}

	private List<Frame> frames(Target t, ShellContext ctx) {
		List<String[]> cf = new ArrayList<>(ctx.callFrames);
		int n = cf.size();
		Ast.Node node = currentNode;
		List<Frame> ret = new ArrayList<>();
		for(int i=0; i <= n; i++) {
			String name = n-1-i >= 0 ? cf.get(n-1-i)[0] : "main";
			String path = n-1-i >= 0 ? cf.get(n-1-i)[1] : mainPath;
			if( path == null || path.isEmpty() || path.equals("main")) {
				path = mainPath;
			}
			path = absolute(path);
			int line;
			if( i == 0 ) {
				line = node != null ? node.line : ctx.currentLine();
			} else {
				try {
					line = Integer.parseInt(cf.get(n-i)[2]);
				} catch (NumberFormatException e) {
					line = 0;
				}
			}
			String fileName = Paths.get(path).getFileName() == null ? path : Paths.get(path).getFileName().toString();
			ret.add(new Frame().setId(t.objectId("f"+i)).setName(name)
					.setSource(new Source().setPath(path).setName(fileName)).setLine(Math.max(line, 1)).setColumn(1));
		}
		return ret;
	}

	/** The shell's variables, except the environment (that is its own scope). */
	private Map<String,Object> shellVariables(ShellContext ctx) {
		Map<String,Object> all = ctx.getVariables();
		Map<String,Object> env = console.getEnvironmentVariable();
		Map<String,Object> ret = new TreeMap<>();
		for(Map.Entry<String,Object> e : all.entrySet()) {
			if( !env.containsKey(e.getKey())) {
				ret.put(e.getKey(), e.getValue());
			}
		}
		return ret;
	}

	private Map<String,Object> arguments(ShellContext ctx) {
		Map<String,Object> ret = new LinkedHashMap<>();
		Object count = ctx.getVariable("#");
		int n = 0;
		try {
			n = Integer.parseInt(String.valueOf(count));
		} catch (NumberFormatException e) {
			// none
		}
		for(int i=1; i <= n; i++) {
			ret.put(String.valueOf(i), ctx.getVariable(String.valueOf(i)));
		}
		return ret;
	}

	private List<Variable> variablesOf(Target t, Map<String,Object> vars) {
		List<Variable> ret = new ArrayList<>();
		for(Map.Entry<String,Object> e : vars.entrySet()) {
			ret.add(new Variable().setName(e.getKey()).setValue(value(t, e.getValue())));
		}
		return ret;
	}

	private Value value(Target t, Object v) {
		if( v == null ) {
			return Value.ofNull();
		}
		if( v instanceof FshList || v instanceof Map ) {
			String id;
			synchronized (lock) {
				id = "o"+handles.size();
				handles.put(id, v);
			}
			int size = v instanceof FshList ? ((FshList) v).getIndexes().size() : ((Map<?,?>) v).size();
			return new Value().setT(v instanceof FshList ? Value.ARRAY : Value.MAP).setId(t.objectId(id))
					.setSize(size).setSummary(text(String.valueOf(v)));
		}
		String s = String.valueOf(v);
		if( s.length() > MAX_TEXT ) {
			return Value.of(s.substring(0, MAX_TEXT)).setTruncated(true).setLength(s.length());
		}
		return Value.of(s);
	}

	private static String text(String s) {
		return s.length() > 100 ? s.substring(0, 100)+"..." : s;
	}

	private ShellContext paused() {
		ShellContext c = currentCtx;
		if( c == null ) {
			throw new LoupeException(LoupeException.WRONG_STATE, "The script has not started");
		}
		return c;
	}

	private static <T> CompletableFuture<T> done(T v) {
		return CompletableFuture.completedFuture(v);
	}

	/** The part of all a request asked for: from start (default 0), at most count (default all). */
	static <T> List<T> page(List<T> all, Integer start, Integer count) {
		if( (start != null && start < 0) || (count != null && count < 0) ) {
			throw new LoupeException(LoupeException.INVALID_PARAMS, "start and count can't be negative");
		}
		int from = Math.min(start == null ? 0 : start, all.size());
		// long: from+count can be more than an int holds
		int to = count == null ? all.size() : (int) Math.min(all.size(), (long) from+count);
		return all.subList(from, to);
	}

	private static String idName(String id) {
		try {
			return ObjectId.parse(id).getName();
		} catch (us.bringardner.parley.dap.json.JsonException e) {
			throw new LoupeException(LoupeException.INVALID_PARAMS, "Bad id "+id);
		}
	}

	private void checkFrame(String id, ShellContext ctx) {
		if( id == null ) {
			return;
		}
		String n = idName(id);
		try {
			int i = Integer.parseInt(n.substring(1));
			if( n.startsWith("f") && i >= 0 && i <= ctx.callFrames.size()) {
				return;
			}
		} catch (NumberFormatException | IndexOutOfBoundsException e) {
			// below
		}
		throw new LoupeException(LoupeException.INVALID_PARAMS, "No frame "+id);
	}

	@Override
	public CompletableFuture<List<ThreadInfo>> threads(Target t) {
		return done(List.of(thread(t)));
	}

	@Override
	public CompletableFuture<FramesResult> frames(Target t, FramesParams p) {
		List<Frame> all = frames(t, paused());
		return done(new FramesResult().setFrames(page(all, p.getStart(), p.getCount())).setTotal(all.size()));
	}

	@Override
	public CompletableFuture<ScopesResult> scopes(Target t, ScopesParams p) {
		ShellContext ctx = paused();
		checkFrame(p.getFrame(), ctx);
		String n = p.getFrame() == null ? "f0" : idName(p.getFrame());
		String frame = n.substring(1);
		// (fsh keeps one set of variables: the function's locals are among them)
		return done(new ScopesResult().setScopes(List.of(
				new Scope().setName("variables").setId(t.objectId("v"+frame)),
				new Scope().setName("arguments").setId(t.objectId("a"+frame)),
				new Scope().setName("environment").setId(t.objectId("e"+frame)).setExpensive(true))));
	}

	@Override
	public CompletableFuture<VariablesResult> locals(Target t, LocalsParams p) {
		ShellContext ctx = paused();
		checkFrame(p.getFrame(), ctx);
		List<Variable> all = variablesOf(t, shellVariables(ctx));
		return done(new VariablesResult().setVariables(page(all, p.getStart(), p.getCount())).setTotal(all.size()));
	}

	@Override
	public CompletableFuture<VariablesResult> children(Target t, ChildrenParams p) {
		ShellContext ctx = paused();
		String n = idName(p.getId());
		List<Variable> all;
		if( n.startsWith("v")) {
			all = variablesOf(t, shellVariables(ctx));
		} else if( n.startsWith("a")) {
			all = variablesOf(t, arguments(ctx));
		} else if( n.startsWith("e")) {
			all = variablesOf(t, new TreeMap<>(console.getEnvironmentVariable()));
		} else {
			Object o;
			synchronized (lock) {
				o = handles.get(n);
			}
			if( o == null ) {
				throw new LoupeException(LoupeException.INVALID_PARAMS, "No object "+p.getId());
			}
			all = new ArrayList<>();
			if( o instanceof FshList ) {
				FshList l = (FshList) o;
				for(int idx : l.getIndexes()) {
					all.add(new Variable().setName(String.valueOf(idx)).setValue(value(t, l.get(idx))));
				}
			} else {
				for(Map.Entry<?,?> e : ((Map<?,?>) o).entrySet()) {
					all.add(new Variable().setName(String.valueOf(e.getKey())).setValue(value(t, e.getValue())));
				}
			}
		}
		return done(new VariablesResult().setVariables(page(all, p.getStart(), p.getCount())).setTotal(all.size()));
	}

	// ---- evaluate, setVariable, completions

	@Override
	public CompletableFuture<EvaluateResult> evaluate(Target t, EvaluateParams p) {
		ShellContext ctx = paused();
		checkFrame(p.getFrame(), ctx);
		String text = p.getExpression().trim();
		if( NAME.matcher(text).matches()) {
			Object v = ctx.getVariable(text);
			if( v == null ) {
				throw new LoupeException(LoupeException.FAILED, text+" is not set");
			}
			return done(new EvaluateResult().setValue(value(t, v)));
		}
		if( EvaluateContext.HOVER.equals(p.getContext()) && (text.contains("$(") || text.contains("`")
				|| text.contains("<(") || text.contains(">("))) {
			throw new LoupeException(LoupeException.FAILED, "A hover doesn't run commands");
		}
		try {
			return done(new EvaluateResult().setValue(Value.of(
					Executor.expanderFor(ctx).string(Parser.fragment(text, Parser.Fragment.WORD)))));
		} catch (RuntimeException e) {
			throw new LoupeException(LoupeException.FAILED, String.valueOf(e.getMessage() != null ? e.getMessage() : e));
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public CompletableFuture<SetVariableResult> setVariable(Target t, SetVariableParams p) {
		ShellContext ctx = paused();
		String n = idName(p.getId());
		String v = p.getValue();
		try {
			if( n.startsWith("v") || n.startsWith("a")) {
				ctx.setVariable(p.getName(), v);
			} else if( n.startsWith("e")) {
				ctx.setEnvironmentVariable(p.getName(), v);
			} else {
				Object o;
				synchronized (lock) {
					o = handles.get(n);
				}
				if( o instanceof FshList ) {
					((FshList) o).set(Integer.parseInt(p.getName()), v);
				} else if( o instanceof Map ) {
					((Map<Object,Object>) o).put(p.getName(), v);
				} else {
					throw new LoupeException(LoupeException.INVALID_PARAMS, "No object "+p.getId());
				}
			}
		} catch (NumberFormatException e) {
			throw new LoupeException(LoupeException.FAILED, p.getName()+" is not an index");
		} catch (LoupeException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new LoupeException(LoupeException.FAILED, String.valueOf(e.getMessage() != null ? e.getMessage() : e));
		}
		return done(new SetVariableResult().setValue(Value.of(v)));
	}

	@Override
	public CompletableFuture<CompletionsResult> completions(Target t, CompletionsParams p) {
		ShellContext ctx = paused();
		String text = p.getText();
		int end = Math.max(0, Math.min(p.getOffset(), text.length()));
		int start = end;
		while(start > 0 && (Character.isLetterOrDigit(text.charAt(start-1)) || text.charAt(start-1) == '_')) {
			start--;
		}
		String prefix = text.substring(start, end);
		Map<String,String> names = new TreeMap<>();
		for(String n : ctx.getVariables().keySet()) {
			names.put(n, "variable");
		}
		boolean afterDollar = start > 0 && (text.charAt(start-1) == '$' || start > 1 && text.charAt(start-1) == '{' && text.charAt(start-2) == '$');
		if( !afterDollar ) {
			for(String n : console.getFunctions().keySet()) {
				names.put(n, "function");
			}
		}
		List<CompletionItem> items = new ArrayList<>();
		for(Map.Entry<String,String> e : names.entrySet()) {
			if( e.getKey().startsWith(prefix)) {
				items.add(new CompletionItem().setLabel(e.getKey()).setType(e.getValue()).setStart(start).setLength(end-start));
			}
		}
		return done(new CompletionsResult().setItems(items));
	}

	// ---- execution control

	@Override
	public CompletableFuture<Void> resume(Target t) {
		synchronized (lock) {
			stepKind = null;
			holding = false;
			lock.notifyAll();
		}
		return done(null);
	}

	@Override
	public CompletableFuture<Void> step(Target t, StepParams p) {
		if( p.getThread() != null && p.getThread() != 1 ) {
			throw new LoupeException(LoupeException.INVALID_PARAMS, "No thread "+p.getThread());
		}
		ShellContext ctx = paused();
		Ast.Node node = currentNode;
		synchronized (lock) {
			stepKind = p.getKind();
			stepDepth = ctx.callFrames.size();
			stepNode = node;
			holding = false;
			lock.notifyAll();
		}
		return done(null);
	}

	@Override
	public CompletableFuture<Void> pause(Target t) {
		synchronized (lock) {
			pauseRequested = true;
		}
		return done(null);
	}

	@Override
	public CompletableFuture<Void> terminate(Target t) {
		ForgroundJob j;
		synchronized (lock) {
			killed = true;
			lock.notifyAll();
			j = job;
		}
		if( j != null ) {
			j.handleSignal(ConsoleSignal.Kill);
			j.interuptJob();
		}
		return done(null);
	}
}
