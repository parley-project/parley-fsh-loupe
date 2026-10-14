# fsh-loupe

A [Loupe](../parley-loupe/PROTOCOL.md) debugging adapter for [fsh](../fsh), the FileSource Shell.
It runs a script under fsh's `DebugContext` (the hooks fsh-ide uses) and answers Loupe
requests, so fsh-ide, or any editor that speaks the Debug Adapter Protocol (through
parley-loupe's bridge), can debug fsh scripts.

```
# Loupe on stdin/stdout, or on a socket
java -cp ... us.bringardner.fsh.loupe.FshAdapterMain
java -cp ... us.bringardner.fsh.loupe.FshAdapterMain --listen 4711

# a DAP debugger an editor can start
java -cp ... us.bringardner.parley.loupe.dap.DapBridgeMain --exec java -cp ... us.bringardner.fsh.loupe.FshAdapterMain
```

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
