# Changelog

## fsh-loupe 1.1.0 (unreleased)

Built on fsh 1.1.0.

### Added

- `FshAdapterMain --listen address:port` listens on that address of this machine (`0.0.0.0` or `*`
  for every address; an IPv6 address in brackets), so a debugger on another machine can connect:
  remote debugging. `--listen port` is still this machine only. A connection from elsewhere has no
  login and no encryption, and the adapter says so on standard error when it starts.
- The launch argument `env`: environment variables (names and values) the script runs with,
  exported, over the adapter's own. fsh-ide gives it the environment a terminal's login shell has.
- As `fsh script` does, the script runs the file `FSH_ENV` names first (before the debugger is
  on, so it doesn't stop in that file).

### Fixed

- Paging frames and variables: a negative `start` or `count` is refused with `INVALID_PARAMS`, and a
  huge `count` (`start + count` past an int) gives the rest of the list; both used to throw an
  internal error.

## fsh-loupe 1.0.0 (2026-10-21)

First release: a Loupe debugging adapter for fsh, built on fsh 1.0.0 and parley-loupe 1.0.0.

- Runs a script (a path on any parley-files file system, or its text) under fsh's `DebugContext`
  and answers Loupe requests on standard input and output, or on a socket (`--listen`).
- Breakpoints with conditions, hit counts and log messages; step over, in and out; pause; the
  entry stop; terminate.
- The call stack (functions and sourced files), variables, positional arguments and the
  environment, arrays and associative arrays; evaluate, setVariable and completions.
- Through parley-loupe's DAP bridge, any editor that speaks the Debug Adapter Protocol can use it.

### Requirements

- Java 11 or later.
