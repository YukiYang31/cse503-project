## `config.debug` vs Java Logging Framework

### Your Current Approach: `config.debug` + `System.out.println`

Across ~53 debug statements in 6 files, every site does:
```java
if (config.debug) System.out.println("Debug== field load: " + lhs + " = " + base + "." + field);
```

### The Alternative: SLF4J `logger.debug()`
```java
private static final Logger logger = LoggerFactory.getLogger(TransferFunctions.class);
logger.debug("field load: {} = {}.{}", lhs, base, field);
```

### Side-by-Side Comparison

| Aspect | `config.debug` | SLF4J + backend |
|---|---|---|
| Setup cost | Zero | 1 dependency + config file |
| Granularity | On/off | TRACE/DEBUG/INFO/WARN/ERROR + per-class |
| Output destination | stdout (mixed with results) | Configurable (stderr, file, etc.) |
| String evaluation | Always builds string, even when off | Lazy — `{}` placeholders only evaluate when enabled |
| Per-class filtering | No | Yes (e.g., debug only `GraphInstantiator`) |
| SootUp noise | Not an issue | Must be filtered — SootUp uses SLF4J internally |
| Learning curve | None | Minimal but nonzero |

### Key Finding: SLF4J API Is Already on Your Classpath

SLF4J 2.0.5 comes transitively from SootUp. That's what produces the `SLF4J: No SLF4J providers were found` warning every time you run. The API is there — you'd only need to add a backend like `slf4j-simple` (1 line in build.gradle).

### Why It's Not Used (5 Reasons)

1. **Academic project, not production.** `if (debug) println(...)` is instantly readable — zero config, zero learning curve.

2. **Single on/off is the right granularity.** The `--debug` flag produces a full trace. There's no current use case for "show me only side-effect checker messages but not transfer functions."

3. **SootUp would pollute the output.** Enabling a logging backend means SootUp's own internal debug messages appear too. Suppressing them requires per-package filtering config — added complexity for no benefit.

4. **`--debug` has bundled domain-specific behavior** (implies `--show-graph` + `--timing`). A logging framework doesn't replace that; you'd still need custom code in `AnalysisConfig`.

5. **It just never came up.** The `"Debug=="` prefix convention works — easy to grep, clearly separated from analysis output, and the flat structure matches the linear method-by-method analysis flow.

### When Would SLF4J Become Worth It?

- Project grows large enough to need **per-component filtering**
- Debug output becomes **performance-sensitive** (many complex `toString()` calls in hot loops where lazy `{}` evaluation matters)
- Tool is used as a **library** embedded in other applications (library code shouldn't write to stdout)
- Multiple developers need **standardized** debug conventions

**Bottom line:** For a single-user academic CLI tool, `config.debug` is the pragmatic choice. The logging framework would add configuration overhead without benefits you currently need.
