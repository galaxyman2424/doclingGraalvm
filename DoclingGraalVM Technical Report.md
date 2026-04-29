# DoclingGraalVM — Technical Report Outline

> **Project:** Embedding IBM Docling into Java via GraalVM Polyglot API
> **Status:** Partially complete — `DocumentConverter` imports successfully; PDF conversion blocked by GraalPy C FFI limitation

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Introduction & Background](#2-introduction--background)
3. [Problem Definition](#3-problem-definition)
4. [Methodology & Approach](#4-methodology--approach)
5. [Technical Development & Findings](#5-technical-development--findings)
6. [IPC Workaround — Subprocess Approach (Working Solution)](#6-ipc-workaround--subprocess-approach-working-solution)
7. [Key Insights](#7-key-insights)
8. [Limitations & Challenges](#8-limitations--challenges)
9. [Proposed Solutions & Alternatives](#9-proposed-solutions--alternatives)
10. [Future Work](#10-future-work)
11. [Conclusion](#11-conclusion)
12. [Appendix](#12-appendix)

---

## 1. Executive Summary

- Brief statement of project goal: in-process Docling integration in Java via GraalVM polyglot
- Summary of what was achieved: `DocumentConverter` imports successfully in GraalPy; PDF conversion blocked at runtime
- Summary of the working solution delivered: subprocess-based IPC (`PythonRunner.java`) confirmed functional
- Key blocker identified: GraalPy's C FFI cannot convert Python callables to native C function pointers (`pypdfium2` CFUNCTYPE callbacks)
- Disposition of findings: shared with the Docling team; three architectural workarounds proposed

---

## 2. Introduction & Background

### 2.1 What Is Docling?
- Open-source AI-powered document conversion toolkit (Python)
- Supports PDF, DOCX, XLSX, HTML, images → structured output (Markdown, JSON)
- Relies heavily on ML libraries (PyTorch, pypdfium2) for layout analysis and OCR

### 2.2 What Is GraalVM?
- High-performance JDK with polyglot support (Java, Python, JavaScript, etc.)
- GraalPy: GraalVM's Python runtime, embedded via the Polyglot API
- Enables multiple languages to run in a single JVM process with shared memory

### 2.3 Existing Docling–Java Integration
- `docling-serve`: HTTP-based Java API — functional but introduces network overhead and deployment complexity
- Goal: replace HTTP IPC with direct in-process interop via GraalPy

### 2.4 Why We Chose GraalVM's Polyglot System
- The core appeal of GraalVM's polyglot API is its promise of true in-process language interoperability. Unlike subprocess calls or HTTP-based IPC, GraalVM's polyglot context allows Java and Python to share the same memory space, pass objects directly between runtimes, and avoid serialization overhead entirely. The alternative we were already using, PythonRunner.java, works by spawning a native Python subprocess and reading stdout — functional, but fragile. The Java process has no visibility into Python's object model, errors surface only as text on stderr, and every invocation pays the full Python startup cost. GraalVM's polyglot API offered a way to eliminate all of that: once the Context is initialized, subsequent context.eval("python", ...) calls run inside an already-warm GraalPy runtime with no process boundary between Java and Python.
### 2.5 Why We Thought It Would Work with Docling
- Docling is written almost entirely in Python. Its core logic; document parsing, layout analysis, Markdown export — lives in pure Python classes like DocumentConverter and DoclingDocument, making it exactly the kind of library GraalPy is designed to host. Where native code does enter the picture, through pypdfium2's thin Python wrapper around Google's PDFium C library, Docling uses Python's standard ctypes module to call into it. GraalVM has a dedicated ctypes compatibility layer built specifically to bridge this kind of Python-to-native boundary. Since pypdfium2 follows that standard pattern, we had good reason to believe GraalPy's ctypes support would handle the interop transparently, keeping the entire conversion pipeline running inside a single JVM process.

---

## 3. Problem Definition

### 3.1 Core Objective
- The existing docling-serve integration exposes Docling's functionality over HTTP, meaning every document conversion requires a full round-trip: the Java application serializes a request, opens a network connection, waits for the Python server to process the document, and deserializes the response. Even on localhost, this introduces measurable latency and adds operational complexity — the Python server must be running as a separate process before the Java application can function at all. The goal of this project was to eliminate that layer entirely by running Docling locally, embedded directly inside the JVM, so that document conversion is a function call rather than a network request.

### 3.2 Technical Constraints
- GraalVM provides a native Java SDK for polyglot execution via its Polyglot API, allowing Java code to create Python contexts, evaluate Python code, and exchange objects across language boundaries within a single process
- GraalPy must faithfully emulate CPython's behavior at two levels: pure Python semantics, and the C extension ABI that Docling's native dependencies (like pypdfium2) rely on to call into compiled C libraries
- The JVM itself must therefore be capable of understanding and executing both managed Python bytecode and the native C code invoked through those extensions — bridging three runtimes in a single process
- The project must remain buildable with standard tooling (Maven, GraalVM JDK 21) so it is reproducible without custom build infrastructure

### 3.3 Scope Boundaries
- Skipped: full ML pipeline (PyTorch, torchvision, accelerate, scipy) — not required for basic PDF conversion
- Skipped: OCR on scanned documents (rapidocr dependency)
- Focus: structured PDF → Markdown conversion via `DocumentConverter`

---

## 4. Methodology & Approach

### 4.1 Team Structure
- 4-person team; 3 members working in isolated repositories to test different environment configurations without interference
- 1 member dedicated to documentation: recording progress, failed attempts, and reproducible setups

### 4.2 Incremental Validation Strategy
- Step 1: Verify GraalPy works at all — run trivial Python (`print("Hello World")`)
- Step 2: Install and import lightweight pure-Python libraries (e.g., `qrcode`) to confirm pip and venv function
- Step 3: Attempt Docling import chain — identify blocking dependencies one at a time
- Step 4: Attempt actual `DocumentConverter.convert()` call with a real PDF

### 4.3 Environment
| Component | Version |
|---|---|
| GraalVM JDK | 21.0.10 |
| GraalPy | 25.0.2 |
| GraalVM Polyglot API (Maven) | 24.1.2 |
| OS | WSL2 Ubuntu |
| Build Tool | Maven |
| Python venv | `~/docling-env` |

### 4.4 Maven Project Configuration
- `graalpy-maven-plugin` used to manage `python-resources/venv` and `python-resources/home` at build time
- `python-embedding` artifact provides `GraalPyResources` API
- Main classes: `EmbedPythonTest.java` (polyglot context test), `PythonRunner.java` (subprocess IPC)

---

## 5. Technical Development & Findings

### 5.1 Stage 1 — Direct Import Attempt
- Installing Docling with all dependencies took hours and consistently failed
- Errors were not immediately obvious — pointed deeper into the dependency graph than surface-level config issues

### 5.2 Stage 2 — Root Cause: C Extensions
- Docling is written in pure Python but depends on native ML libraries
- **Primary blocker:** PyTorch (`torch`) — requires C extensions for GPU/CPU backends that GraalPy cannot execute
- GraalVM's own documentation classifies PyTorch compatibility as "mostly experimental"
- Two confirming tests:
  - Full Docling `convert()` call → Torch pulled in as a transitive dependency → failure
  - Isolated GraalVM program importing Torch directly → failure at the same native boundary

### 5.3 Stage 3 — Dependency Isolation
- Reinstalled Docling with `--no-deps`; manually resolved ~15 transitive dependencies
- Batched pure-Python deps: `beautifulsoup4`, `marko`, `openpyxl`, `pluggy`, `pylatexenc`, `python-docx`, `python-pptx`, `requests`, `lxml`, `polyfactory`, `pypdfium2`, `rtree`
- Compiled `lxml` from source against system `libxml2`/`libxslt`

### 5.4 Stage 4 — Targeted Patches Applied

| Issue | Root Cause | Fix Applied |
|---|---|---|
| `POINTER(None)` TypeError | GraalPy ctypes rejects `POINTER(None)` | Patched `pypdfium2_raw/bindings.py` — replaced all occurrences with `c_void_p` via `sed` |
| `LookupError: euc_jis_2004` | `charset_normalizer` 3.4+ enumerates all codecs at import; GraalPy missing `euc_jis_2004` | Pinned `charset_normalizer==3.3.2` |
| `PackageNotFoundError: docling-ibm-models` | Installed with `--no-deps`; metadata lookup still runs at import | Installed `docling-ibm-models` with `--no-deps` to satisfy version check |
| `lxml` build failure | Missing system headers | `sudo apt-get install libxml2-dev libxslt1-dev` |
| `ModuleNotFoundError: filetype` | Not pulled in with no-deps install | Installed `filetype` separately |

### 5.5 Stage 5 — Current Blocker: pypdfium2 Callback Failure
- After all patches, `from docling.document_converter import DocumentConverter` **succeeds**
- Calling `converter.convert(pdf_path)` fails at runtime:
  - `pypdfium2`'s `get_bufreader()` function attempts to register a Python callable as a native C function pointer via `set_callback` (a `CFUNCTYPE` object)
  - GraalPy throws: `NotImplementedError: ctypes function call could not obtain function pointer`
  - All Docling PDF backends (v2, v4, default) route through this code path
- **Confirmed via isolation test** ([CTypeFuncTest.java](https://github.com/galaxyman2424/doclingGraalvm/blob/CTypeTest/src/main/java/CTypeFuncTest.java)): the identical `CFUNCTYPE` callback runs successfully in standalone GraalPy but fails immediately inside a Java `Context.newBuilder("python")` polyglot context — proving the embedding is the cause, not GraalPy's ctypes implementation in general
- **Root cause:** Standalone GraalPy manages its own memory and native interop freely. When embedded inside the JVM polyglot context, the JVM's garbage collector takes over memory management — and this is fundamentally incompatible with how Python's C extensions work. Python C extensions use reference counting and expect to place objects directly into native, unmanaged memory. The JVM cannot trace or permit this: managed objects living on the Java heap cannot be materialized as raw C function pointers in native memory, which is exactly what `CFUNCTYPE` requires. On top of this, when C extension code is compiled for GraalPy it is represented as LLVM bitcode, which the JVM's own bytecode layer cannot directly interpret — the two memory models and execution representations cannot coexist at the boundary where `pypdfium2` needs to hand a Python callable to native PDFium code.

---

## 6. IPC Workaround — Subprocess Approach (Working Solution)

> This is the approach that **works end-to-end** today. See [`EmbededPythonTest.java`](<https://github.com/galaxyman2424/doclingGraalvm/blob/processbuilder/src/main/java/EmbedPythonTest.java>) for the full implementation.

### 6.1 How It Works
- Java constructs a self-contained Python script as a string at runtime
- The script imports `DocumentConverter`, runs `convert()`, and writes the Markdown output to a file
- Java launches the **native CPython executable** (inside the Docling venv) as a subprocess via `ProcessBuilder`
- Java captures stdout/stderr and the exit code; the output file is read back into Java

### 6.2 Architecture Diagram

```
Java (JVM)
   │
   ├─ Builds Python script string
   ├─ Spawns ProcessBuilder → native Python (venv/Scripts/python.exe)
   │       │
   │       └─ Runs Docling DocumentConverter.convert(pdf_path)
   │               └─ Writes Markdown → output.txt
   │
   └─ Reads output.txt back into Java
```

### 6.3 Key Implementation Details
- Path configuration is done via string constants at the top of `main()` — easily adapted
- `validateSetup()` checks that both the PDF file and Python executable exist before attempting conversion
- `ProcessBuilder.redirectErrorStream(true)` merges stderr into stdout for unified capture
- Exit code checked post-`waitFor()` to distinguish success from Python-level failures
- No GraalVM polyglot API involved — works on any standard JDK

### 6.4 Tradeoffs vs. In-Process Embedding

| Aspect | Subprocess (working) | GraalPy Embedding (blocked) |
|---|---|---|
| Works today | ✅ Yes | ❌ No |
| In-process (shared memory) | ❌ No | ✅ Yes |
| Startup overhead | Higher (new process per call) | Lower (context reuse) |
| Deployment complexity | Requires Python venv on host | Self-contained JAR |
| Full Docling ML pipeline | ✅ Yes (native CPython) | ❌ Blocked (C FFI) |
| Production suitability | ✅ Suitable | ❌ Not yet |

### 6.5 Why This Is Still Valuable
- Delivers the end-to-end Docling PDF → Markdown pipeline from Java today
- No GraalVM-specific patches or workarounds required
- Can be used as a drop-in for `docling-serve` without HTTP overhead in single-host deployments
- Provides a stable baseline while polyglot embedding is pursued upstream

---

## 7. Key Insights

- GraalPy is viable for pure-Python libraries but breaks down at native C extension boundaries — specifically ctypes `CFUNCTYPE` callbacks
- Docling's dependency on PyTorch (via `docling-ibm-models`) is the primary blocker for ML-powered layout analysis; basic conversion has a narrower dependency surface
- Incremental dependency isolation (installing with `--no-deps`, then resolving manually) is the correct strategy for embedding large Python frameworks in GraalPy
- The `POINTER(None)` → `c_void_p` patch is a generalizable fix for any package using void pointer ctypes signatures under GraalPy
- The subprocess IPC pattern is a pragmatic, production-grade fallback that should be the default recommendation for student-level projects

---

## 8. Limitations & Challenges

### 8.1 Technical Limitations
- GraalPy cannot materialize native C function pointers from Python callables (CFUNCTYPE callbacks) — architectural, not a configuration issue
- PyTorch C extensions are incompatible with GraalPy; GraalVM classifies Torch support as "mostly experimental"
- `huggingface-hub` and `typer` version conflicts remain unresolved (not yet downgraded to compatible versions)
- `EmbedPythonTest.java` polyglot context has not been tested end-to-end against `DocumentConverter`

### 8.2 Project Constraints
- Student team with a fixed timeline — complex solutions (JNI, GraalVM patching) are out of scope
- WSL2 environment adds cross-platform complexity for Windows/Linux path handling
- GraalPy-compatible wheels are not available for most ML libraries; source compilation required

---

## 9. Proposed Solutions & Alternatives

> Full analysis of each solution is documented in [`Solutions.md`](<https://github.com/galaxyman2424/doclingenv/blob/main/GRAALPY_DOCLING_COMPATIBILITY.md>).

### 9.1 Solution 1 — JNI Wrapper for PDFium
- **What:** Direct Java-to-C bridge via JNI, bypassing pypdfium2 and Python ctypes entirely
- **Effort:** ~400–600 hours; requires C expertise, cross-platform toolchain, JVM internals knowledge
- **Verdict:** Not feasible for a student team

### 9.2 Solution 2 — Patch GraalVM's LLVM Bitcode Interop
- **What:** Add trampolining support to GraalVM's native interop layer so CFUNCTYPE callbacks can be converted to C function pointers
- **Effort:** 600+ hours; requires GraalVM source-level expertise; patch may be rejected upstream
- **Verdict:** Effectively impossible for a student team; appropriate for a GraalVM core contributor

### 9.3 Solution 3 — Replace pypdfium2 with a Pure-Python PDF Library
- **What:** Swap Docling's PDF backend for `pdfplumber` or `pypdf` (pure Python, no C extensions)
- **Effort:** ~100–150 hours
- **Verdict:** Technically feasible but eliminates Docling's core AI layout analysis; delivers a significantly degraded product

### 9.4 Recommended Approach (Pragmatic)
- **Use the subprocess IPC approach** (`PythonRunner.java`) for any production or demo use
- Pursue Solution 3 only if pure text extraction (no layout AI) is acceptable
- Monitor GraalPy's ctypes roadmap for CFUNCTYPE callback support upstream

---

## 10. Future Work

- **Patch `docling_parse_backend.py`** to pass PDF file paths as strings directly to `pdfium.PdfDocument()` instead of `BytesIO` buffers — this would invoke `FPDF_LoadDocument` (no callbacks) instead of `FPDF_LoadMemDocument` (broken callback path)
- **Downgrade `huggingface-hub`** to `<1.0` and `typer` to `<0.22.0` to clear remaining version conflicts
- **Test `EmbedPythonTest.java` end-to-end** once PDF path-based loading is confirmed working
- **Write a reproducible setup shell script** capturing all manual patches (`sed` commands, pinned versions, `apt` installs) so the environment can be rebuilt without repeating each fix
- **Engage GraalVM upstream** — file an issue or contribute a fix for CFUNCTYPE callback conversion; this would unblock not just Docling but any Python library using ctypes callbacks under GraalPy
- **Explore GraalVM Native Image** compilation of the subprocess runner for reduced startup latency

---

## 11. Conclusion

- The team was unable to achieve fully in-process Docling PDF conversion via GraalPy due to a fundamental C FFI limitation in GraalVM's CFUNCTYPE callback handling
- The failure was not a configuration error — it is an architectural gap in GraalPy's current implementation
- Despite this, the team successfully: isolated the exact failure point in `pypdfium2`'s buffer reader; got `DocumentConverter` to import cleanly in GraalPy; and delivered a working end-to-end PDF → Markdown pipeline via subprocess IPC
- Findings and repositories were shared with the Docling team
- The subprocess approach (`PythonRunner.java`) is the recommended production path until GraalPy's C FFI matures
- This project surfaced concrete, actionable data points for the GraalPy and Docling ecosystems — a meaningful contribution even without a fully working in-process integration

---

## 12. Appendix

### A. Repository Links
- Subproccess IPC Implementation: `<https://github.com/galaxyman2424/doclingGraalvm/blob/processbuilder/src/main/java/EmbedPythonTest.java>`
- GraalPy polyglot context test harness: `<https://github.com/galaxyman2424/doclingGraalvm/blob/graalpytester/src/main/java/EmbedPythonTest.java>`
- pom.xml: `<https://github.com/galaxyman2424/doclingGraalvm/blob/graalpytester/pom.xml>`
- Python Resources: `<https://github.com/galaxyman2424/doclingenv/tree/main/lib/python3.12/site-packages>`

### B. Key Files
| File | Description |
|---|---|
| `PythonRunner.java` | Subprocess IPC implementation (working solution) |
| `EmbedPythonTest.java` | GraalPy polyglot context test harness |
| `pom.xml` | Maven build config with GraalPy plugin and dependencies |
| `python-resources/` | Managed GraalPy venv (created by Maven plugin at build time) |

### C. Dependency Notes
| Package | Notes |
|---|---|
| `docling` | Installed `--no-deps`; transitive deps resolved manually |
| `pypdfium2` | Patched `bindings.py`: `POINTER(None)` → `c_void_p` |
| `charset_normalizer` | Pinned to `3.3.2` to avoid codec enumeration crash |
| `docling-ibm-models` | Installed `--no-deps` to satisfy version metadata only |
| `lxml` | Compiled from source; requires `libxml2-dev`, `libxslt1-dev` |
| `torch`, `torchvision`, `scipy`, `rapidocr` | Intentionally skipped |

### D. Known Remaining Issues
- `huggingface-hub 1.7.1` incompatible (needs `<1.0`) — not yet downgraded
- `typer 0.24.1` incompatible (needs `<0.22.0`) — not yet downgraded
- `EmbedPythonTest.java` not yet run end-to-end
- All PDF backends ultimately route through the broken `pypdfium2` callback path
