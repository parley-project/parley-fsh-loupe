# fsh-loupe

A [Loupe](../parley-loupe/PROTOCOL.md) debugging adapter for [fsh](../fsh), the FileSource Shell.
It runs a script under fsh's `DebugContext` (the hooks fsh-ide uses) and answers Loupe
requests, so fsh-ide, or any editor that speaks the Debug Adapter Protocol (through
parley-loupe's bridge), can debug fsh scripts.

```
# Loupe on stdin/stdout, or on a socket
java -cp ... us.bringardner.fsh.loupe.FshAdapterMain
java -cp ... us.bringardner.fsh.loupe.FshAdapterMain --listen 4711

# Loupe on a socket other machines can reach (see below)
java -cp ... us.bringardner.fsh.loupe.FshAdapterMain --listen 0.0.0.0:4711

# a DAP debugger an editor can start
java -cp ... us.bringardner.parley.loupe.dap.DapBridgeMain --exec java -cp ... us.bringardner.fsh.loupe.FshAdapterMain
```

A debugger that connects to the port may speak Loupe or DAP: the adapter tells which from its
first message, so an editor's DAP client can connect straight to the port (no bridge to start),
and so can fsh-ide's Debug Remote in either protocol. The same holds for the port a script waits
on (below). Over DAP a debugger doesn't get a script's text (`fsh/source` is Loupe's).

`--listen port` accepts connections from this machine only. `--listen address:port` listens
on that address of this machine (`0.0.0.0` or `*` for all of them; an IPv6 address in brackets),
so a debugger on another machine can connect. That connection has no login and no encryption,
and a debugger can run any command as the user the adapter runs as: use it only on a network
where everyone who can reach the port is trusted. The adapter says so on standard error when it
starts.

When the debugger's connection closes, a script it launched is ended.

## Attach: a script that waits for its debugger

```
fsh --wait-for-debugger 4711 script.fsh arguments
fsh --wait-for-debugger 0.0.0.0:4711 script.fsh arguments
```

With fsh-loupe on fsh's class path (or in one of its plugin folders), fsh starts the script as
`fsh script` does, says on standard error that it is waiting, and waits at that port (written
as `--listen`'s, with the same warning) until a debugger connects and sends `attach` (no
arguments). The script then runs under that debugger, in the shell that was started: its
input and output are the shell's own, so the debugger gets no `output` events. Attach with
`startPaused` to set breakpoints before the first command. A connection that closes without
attaching is forgotten and the script goes on waiting; once a debugger has attached the port
is closed, and when that debugger goes the script runs on without one. `terminate` ends the
script. The adapter of a waiting script answers `attach`, not `launch`; `FshAdapterMain`
answers `launch`, not `attach`.

## The script's text: `fsh/source`

A debugger on another machine has no copy of the script. `fsh/source` (a method of this
adapter, not part of Loupe; no target) gives it: params `{"path": ...}`, result
`{"path": ..., "text": ...}`. The path is read where the adapter runs (any parley-files file
system); for a waiting script's own path the text is what the shell read when it started.

Launch arguments: `script` (a path; any parley-files file system) or `source` (the text) with
`name` (`$0`), and `args`.

## What works

- **Stops:** breakpoints (conditions are fsh commands, such as `[ $i -gt 3 ]`; hit counts; log
  messages, in which `$var` is expanded), step over / in / out, pause, the entry stop
  (`startPaused`), terminate.
- **Stack:** fsh's call frames: functions and sourced files, with the line each was called from.
- **Values:** variables, the positional arguments and the environment as scopes; arrays and
  associative arrays are values with parts; long text is cut at 10000 characters.
- **evaluate:** a variable name, or a word expanded in the stopped script's context
  (`$x`, `${a[1]}`, `$((n+1))`). A hover does not run `$( )`.
- **setVariable, completions** (variables and functions), and the script's output as `output`
  events.

## What does not (yet)

- Only the script's own thread is debugged: pipeline stages, `&` jobs and subshells that run
  on other threads don't stop.
- fsh keeps one set of variables per shell, so every frame shows the same ones (the running
  functions' locals are among them).
- The script has no standard input.
- evaluate expands a word; it does not run a command line.
- No exception filters: an fsh error is a status, not an exception.

## Building

fsh-loupe needs `us.bringardner:fsh` and `us.bringardner.parley:parley-loupe` (both
`1.0.0-SNAPSHOT`) in the local repository: `mvn install -DskipTests` in each. fsh's own build
needs the Parley FTP, SFTP and SSH modules installed first.
