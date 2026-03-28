# Current Dynamic Dispatch Behavior

This note describes the current implementation after moving dynamic-dispatch unioning out of the transfer function and into summary construction.

## Short Version

For virtual and interface methods, the cache now stores a summary for the declared method itself.

Example:

```java
List<String> xs = new ArrayList<>();
xs.add("a");
```

The analysis wants an exact summary for `List.add(...)`. That summary is built as:

```text
summary(List.add) =
  own body of List.add (if any)
  U summaries of direct overrides
  U summaries propagated from deeper descendants
```

So by the time a callsite looks up `List.add(...)`, the union has already been done.

## Current Pipeline

### 1. Call graph construction still resolves concrete runtime targets

`CallGraphBuilder` still uses bounded CHA-style target resolution for virtual/interface invokes.

This serves two purposes:

- it discovers reachable library/JDK classes for analysis
- it makes the bottom-up analysis order depend on possible concrete implementations

So callers are still ordered after likely runtime targets.

### 2. Override graph now covers the full analyzed universe

The override graph is no longer restricted to only the explicit input classes.

It now includes:

- user/input classes
- discovered JDK/library classes
- resolved superclasses and interfaces for those classes

Edges are built from declared/base method to direct overriding method, including interface-to-class and interface-to-abstract-class links when those declarations are available.

### 3. Summaries are merged before they enter the exact-match cache

`SummaryCache` is now exact-match only.

When a concrete method is analyzed:

1. its direct summary is produced from the method body
2. that summary is normalized into a contributor-specific namespace
3. it is merged into the cache entry for its exact signature
4. the merged summary is propagated upward through the reverse override graph

This means:

- a concrete method stores its own implementation summary
- an abstract/interface/base method stores the union of its implementations
- repeated SCC updates remain stable because contributor nodes are deterministically namespaced

### 4. Transfer functions do exact lookup only

`TransferFunctions.handleInvoke()` now does:

1. exact lookup of the declared signature
2. viewpoint adaptation / graph instantiation
3. conservative fallback if no exact summary exists

It no longer unions override summaries at the callsite.

## Cache Policy

There are two intended modes:

### Building the JDK cache

When the analysis is running in JRT/JDK-cache-building mode, merged library summaries may be written back to `jdk-cache/`.

That allows newly discovered implementations to refine persisted JDK summaries.

### Normal user analysis

When analyzing user code, the persisted JDK cache is treated as a seed only.

In-memory merged summaries may include user overrides for the current run, but those user-induced unions are **not** written back to disk.

So a user-defined override should affect the current analysis result without permanently contaminating the shared JDK cache.

## Practical Limits

- dynamic target resolution is still capped to avoid explosion
- BFS library discovery is still bounded
- forbidden-package filters still apply

So this is still a bounded, conservative approximation of dynamic dispatch, but the union now lives in the method summaries themselves rather than in the callsite transfer logic.
