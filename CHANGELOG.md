# Changelog

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
