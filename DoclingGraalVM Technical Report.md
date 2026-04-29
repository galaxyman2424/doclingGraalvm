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
8. [Proposed Solutions & Alternatives](#8-proposed-solutions--alternatives)
9. [Future Work](#9-future-work)
10. [Conclusion](#10-conclusion)
11. [Appendix](#11-appendix)

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

> This is the approach that **works end-to-end** today. See [`IPCTest.java`](https://github.com/galaxyman2424/doclingGraalvm/blob/processbuilder/src/main/java/IPCTest.java) for the full implementation.

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

### 6.4 Tradeoffs vs. All Three Approaches

It's worth being precise about what each tier actually eliminates. `docling-serve` communicates over HTTP: data traverses the full network stack — TCP handshake, socket buffer copies, HTTP framing, JSON serialization, kernel `send()`/`recv()` syscalls — even when client and server are on the same physical machine. The subprocess approach eliminates every one of those layers.

But `ProcessBuilder` is still IPC in the strict OS sense. On Linux (including WSL2), it ultimately invokes `clone()`/`execve()`. The kernel allocates a new process descriptor, copies the file descriptor table, loads the Python interpreter, and initializes the full venv — a cold-start cost paid on every conversion call. Communication back to Java flows through anonymous pipes or the filesystem, both kernel-mediated. There is no shared heap.

The GraalVM embedding goal is to eliminate the OS boundary entirely: `DocumentConverter.convert()` would return a Python object directly into the Java heap via the polyglot Value API — no `fork`, no pipe, no filesystem round-trip.

| Aspect | `docling-serve` (HTTP) | Subprocess `ProcessBuilder` | GraalPy Embedding |
|---|---|---|---|
| Works today | ✅ Yes | ✅ Yes | ❌ No |
| IPC mechanism | TCP socket + HTTP | `clone()`/`execve()` + pipe/file | None — shared JVM heap |
| Per-call overhead | ~ms + serialization cost | ~seconds cold start + pipe copies | ~µs, zero serialization |
| In-process (shared memory) | ❌ No | ❌ No | ✅ Yes |
| Deployment complexity | Requires running server | Requires Python venv on host | Self-contained JAR |
| Full Docling ML pipeline | ✅ Yes | ✅ Yes (native CPython) | ❌ Blocked (C FFI) |
| Production suitability | ✅ Suitable | ✅ Suitable | ❌ Not yet |

### 6.5 Why This Is Still Valuable
```
This project achieved the middle tier of a three-level hierarchy:
HTTP (docling-serve)           ← network stack + TCP + serialization  [eliminated ✅]
↓
OS subprocess (ProcessBuilder) ← clone()/execve() + pipe/file I/O     [remains ⚠️]
↓
GraalVM polyglot context       ← shared JVM heap, direct object passing [blocked ❌]
```
Removing HTTP is a real and meaningful improvement; no network stack, no serialization overhead, no dependency on a running server process. For single-host deployments, the subprocess approach is a practical drop-in replacement for `docling-serve`. The `clone()`/`execve()` boundary and pipe/file IPC remain, but these are substantially cheaper than TCP. The final tier; true in-process embedding with zero OS boundary — is almost certainly what IBM and the Docling team want as the production target, and it remains blocked at GraalPy's C FFI layer as documented in the sections above.

### 6.6 The IPC Layer That Remains

For completeness, the full per-tier cost breakdown:

```
HTTP (docling-serve)
└─ TCP socket → kernel network stack → loopback → deserialize
overhead: ~milliseconds per call + per-byte serialization cost
OS subprocess (ProcessBuilder)

└─ clone()/execve() → Python init → pipe/file I/O → waitpid()
overhead: ~seconds cold start, pipe buffer copies per call
GraalVM polyglot context (blocked)

└─ context.eval() → direct object graph in shared JVM heap
overhead: ~microseconds, zero serialization, zero OS boundary
```
---

## 7. Key Insights

### 7.1 GraalPy Is Viable for Pure-Python Libraries — Until It Isn't

GraalPy handles pure-Python code well. Libraries with no native dependencies installed and ran correctly throughout this project, and the GraalPy import machinery, venv resolution, and site-packages layout all behave as expected. The wall is hit precisely at the C extension boundary, specifically when a package uses `ctypes.CFUNCTYPE` to register a Python callable as a native C function pointer via `set_callback`. GraalPy's C FFI implementation can handle simple type definitions and struct layouts, but it cannot perform the callable-to-function-pointer conversion that `CFUNCTYPE` requires. This is not a configuration issue or a missing package. It is a fundamental gap in GraalPy's native interop layer that would require a patch to GraalVM itself to resolve. Any Python library that crosses this boundary (pypdfium2, and by extension any library using a C-backed buffer reader) will fail the same way.

### 7.2 PyTorch Is the Dependency That Poisons the Well

Docling's ML-powered layout analysis depends on `docling-ibm-models`, which in turn requires PyTorch. PyTorch is not a Python library with a C extension; it is effectively a C++/CUDA runtime that happens to have a Python interface. GraalVM's own documentation classifies Torch support as "mostly experimental," and in practice it fails entirely during import due to missing native backends. This means the full Docling pipeline (AI layout analysis, table extraction, OCR) cannot run under GraalPy in any configuration today. What can run is the narrower dependency surface of basic document conversion, covering DOCX, HTML, and Markdown backends where PyTorch is never invoked. Understanding this distinction is important for scoping any future fix: removing the PDF blocker alone still does not give you the ML pipeline.

### 7.3 The `POINTER(None)` to `c_void_p` Patch Is a Generalizable GraalPy Fix

CPython silently accepts `POINTER(None)` as a valid void pointer idiom in ctypes. It is widely used in auto-generated bindings files as a stand-in for `void*`. GraalPy rejects it outright at module load time with a `TypeError`, before any conversion is attempted. The fix is to replace every occurrence with `c_void_p`, which is semantically identical and is the ctypes-correct way to express a void pointer. This is not a pypdfium2-specific patch. Any package whose bindings were auto-generated against a C header using `void*` will hit the same crash under GraalPy. The fix can be applied in one pass via `sed` and should be treated as a standard GraalPy porting step for any ctypes-heavy package. It is worth noting that this patch gets past module load but does not fix the deeper `CFUNCTYPE` callback failure, as the two are separate issues at different layers of the ctypes stack.

### 7.4 The Subprocess Approach Is Not a Compromise — It Is the Correct Default

Given the C FFI blockers documented above, reaching for `ProcessBuilder` with a native CPython venv is not admitting defeat. It delivers the complete Docling pipeline, including full ML layout analysis, OCR, and table extraction, because it runs on unmodified CPython where none of the GraalPy limitations apply. For a Java application that needs Docling today, it is production-grade. The `clone()`/`execve()` overhead is a one-time cold-start cost per conversion, not a per-page or per-call tax, and the pipe/file IPC is cheap relative to what Docling itself is doing internally. The GraalVM polyglot embedding path remains the right long-term goal, with zero OS boundary, shared heap, and direct object passing, but it is blocked upstream and outside the control of any application developer. Until GraalPy's native interop layer matures, the subprocess pattern should be the default recommendation for any Java-Docling integration.

- **Incremental dependency isolation is the only viable install strategy.** Installing Docling via `pip install docling` in a GraalPy venv will fail. The dependency graph is too large and too native-heavy to resolve cleanly in one pass. The correct approach is to install with `--no-deps` first, then use pip's conflict report to identify missing transitive dependencies and install them in batches grouped by complexity, starting with pure-Python packages and moving to packages requiring compilation. This lets you pinpoint the exact failure for each dependency rather than getting a wall of errors from a monolithic install.

- **`charset_normalizer` must be pinned to `3.3.2`.** Version 3.4+ proactively enumerates every Python codec at import time. GraalPy does not implement the full codec registry (`euc_jis_2004` is missing), causing a hard `LookupError` on import. Pinning to `3.3.2` avoids the scan entirely and is a required fixup for any GraalPy environment pulling in `requests` or similar HTTP libraries.

---

## 8. Proposed Solutions & Alternatives

> Full analysis of more solutions are documented in [`Solutions.md`](<https://github.com/galaxyman2424/doclingenv/blob/main/GRAALPY_DOCLING_COMPATIBILITY.md>).

### 8.1 Solution 1 — JNI Wrapper for PDFium
- What it does:
  - Instead of relying on pypdfium2 (which wraps the native libpdfium library via Python ctypes), a JNI wrapper would create a direct Java-to-C bridge. This would call libpdfium.so directly from Java via a compiled .so or .dll native library, bypassing Python entirely for PDF loading and eliminating the pypdfium2 ctypes callback problem. Parsed data (text, images, metadata) would be returned to the GraalPy DocumentConverter as Python-callable objects through the polyglot API.
- Why it's not feasible for a student team:
  - JNI requires deep knowledge of C, Java memory management, JVM internals, and pointer manipulation — well beyond typical CS coursework. Even a simple JNI wrapper requires hundreds of lines of C code for type marshalling (converting Java objects ↔ C structs), error handling, and manual memory management, often at a 5:1 code-to-feature ratio. PDFium itself is approximately 500,000 lines of code, meaning exposing even a meaningful subset of its API would take weeks of work.
  - Debugging is particularly brutal: segmentation faults from C code crash the entire JVM with no Python-style stack trace, requiring native debuggers like gdb or lldb. Memory leaks in the C layer don't show up in Java profilers and often only surface in production. Cross-platform compilation adds another layer of pain — the JNI library must be compiled separately for Windows (MSVC), Linux (GCC), and macOS (Clang), and any toolchain version mismatch between team members breaks the build for everyone.

### 8.2 Solution 2 — Patch GraalVM's LLVM Bitcode Interop
- What it does:
  - GraalVM compiles Python code and C extensions to LLVM bitcode, then to native machine code. At the boundary, C FFI calls pass through a custom native interop layer. This solution would involve patching that interop layer to support CFUNCTYPE callback conversion — the specific operation that fails when pypdfium2 tries to register its buffer-reader callback via set_callback. Concretely, it would require implementing a trampolining mechanism that converts Python callables into native C function pointers on-the-fly, then submitting the patch upstream to GraalVM or maintaining a permanent fork.
- Why it's effectively impossible for a student team:
  - GraalVM's source spans approximately 2 million lines of Java code covering polyglot runtimes, JIT compilers, and the C FFI layer. Identifying which files to modify and understanding the invariants they must preserve requires weeks of source-reading before writing a single line of code. The trampoline code itself must bridge two radically different calling conventions (Python's garbage-collected stack frame model vs. C's ABI), and must be written in C or raw LLVM IR. A single mistake causes callbacks to crash, skip arguments, or silently corrupt memory. Each test cycle requires building GraalVM from source (1–2 hours) and running the full test suite (another hour or more).
  - Beyond the technical difficulty, the patch would need to be accepted by the GraalVM maintainers, who have previously described ctypes callback support in GraalPy as a low priority. If rejected, the team would be stuck maintaining a private GraalVM fork indefinitely — a burden that compounds with every upstream security fix and release. Callbacks also behave differently across CPU architectures (x86-64, ARM, RISC-V) due to stack alignment, register preservation, and ABI differences, meaning a patch that works on one platform may silently break another.

### 8.3 Solution 3 — Replace pypdfium2 with a Pure-Python PDF Library
- What it does:
  - Rather than fixing the ctypes callback failure, this approach sidesteps it by swapping Docling's PDF backend for a pure-Python library that doesn't use C extensions at all. The realistic candidates are pdfplumber and pypdf — both are pure Python and would run without issue in GraalPy. Libraries like PyMuPDF (fitz) and pikepdf appear pure-Python on the surface but wrap MuPDF and pikepdf C++ backends respectively, hitting the same FFI limitations as pypdfium2.
- Why it delivers a fundamentally degraded product:
  - Docling's core value is its AI-powered layout analysis: understanding document structure (reading order, headers, footers, tables, columns) and performing OCR on scanned or image-based PDFs. All of that functionality depends on pypdfium2 as the rendering backend — it's not a configuration option but a deep architectural dependency. pdfplumber and pypdf do text extraction only, and struggle with anything beyond simple single-column documents.
  - Swapping backends isn't a configuration change — it requires rewriting docling_parse_backend.py wholesale, replacing all pypdfium2 API calls with pdfplumber equivalents, and implementing from scratch the image extraction and encrypted PDF handling that pdfplumber lacks. The DocumentConverter.convert() return object carries layout metadata that downstream code relies on; without a real layout engine behind it, that metadata would be absent or fabricated.
  - Performance is also a significant regression: pdfplumber can take 30–120 seconds to process a 100-page PDF where pypdfium2 handles the same document in 1–5 seconds, making batch processing or any server workload impractical.

---

## 9. Future Work

- Patch docling_parse_backend.py (File-Path Based PDF Loading)
  - Locate the pdfium.PdfDocument(self.path_or_stream) call in docling_parse_backend.py and add a conditional branch: if path_or_stream is a str or Path, pass it directly to pdfium.PdfDocument() so PDFium uses FPDF_LoadDocument (no callbacks) instead of FPDF_LoadMemDocument
  - Test the patch by running a simple conversion on a local PDF through GraalPy and confirming no Cannot convert Object pointer to native error is raised; if the error shifts to a different call site, trace the new stack and apply the same path-vs-buffer branching logic there

- Downgrade huggingface-hub and typer
  - Run pip install "huggingface-hub<1.0" "typer<0.22.0" in the docling-env and verify with pip check that no new conflicts are introduced by the downgrades
Re-run the from docling.document_converter import DocumentConverter import in GraalPy to confirm the version conflict warnings are gone and the import chain is still clean

- Test EmbedPythonTest.java End-to-End
  - Once PDF path-based loading is confirmed working in GraalPy standalone, run mvn clean compile exec:java and check that the GraalVM polyglot context successfully calls DocumentConverter.convert() on a test PDF in-process without falling back to subprocess
If the polyglot context fails, isolate whether the failure is in the Java-side context options (executable path, venv path, PythonPath) or in the Python code itself by running the same Python snippet in GraalPy directly first, then replicating the exact options in EmbedPythonTest.java

- Write a Reproducible Setup Shell Script
  - Capture every manual step taken so far — the sed patch to pypdfium2_raw/bindings.py, the charset_normalizer pin, the apt installs for libxml2-dev and libxslt1-dev, and each batch pip install — into a single setup.sh so any team member can rebuild the environment from scratch with one command
Add a validation block at the end of the script that runs python -c "from docling.document_converter import DocumentConverter; print('OK')" in the venv and exits non-zero if it fails, so breakage is caught immediately rather than silently

- Explore GraalVM Native Image for the Subprocess Runner
  - Profile the current PythonRunner.java subprocess approach to establish a baseline startup latency, then attempt a Native Image build with native-image -cp target/classes PythonRunner and compare cold-start times
  - If Native Image build fails due to reflection or polyglot API usage, add the necessary --initialize-at-build-time flags and a reflection configuration JSON, documenting each flag so the build is reproducible

---

## 10. Conclusion

This project started with the goal of getting Docling running directly inside Java using GraalVM, so we could avoid the overhead and complexity of spinning up a separate Python service. On paper, GraalVM looked like the right tool for this since it supports running Python and Java in the same process.

In practice, we were able to get parts of Docling working. The core Python code and DocumentConverter import cleanly inside GraalPy, which showed that most of the dependency stack can actually be resolved. The problem shows up specifically at the PDF conversion step, where Docling relies on pypdfium2, which uses ctypes.CFUNCTYPE to pass Python callbacks into native C code. Inside a GraalVM polyglot context, this fails with a C FFI limitation where Python callables cannot be converted into native function pointers. This is not something we could fix with dependency changes or patching—it is a limitation of GraalPy’s current embedding model.

Because of that, full in-process execution was not achievable. However, we were still able to build a fully working end-to-end solution using a subprocess-based approach in PythonRunner.java. This runs Docling through a standard CPython environment and communicates back to Java through IPC. While it does introduce a process boundary, it completely bypasses the GraalPy limitations and successfully handles full PDF → Markdown conversion.

Overall, the main outcome of this project is not just the working subprocess implementation, but the fact that we were able to isolate the exact failure point in GraalVM’s C FFI layer. That makes it clear where the current system breaks and what would need to change in order for true in-process Docling execution to work in the future. Until then, the subprocess approach is the most reliable and production-ready option.

---

## 11. Appendix

### A. Repository Links
- Subproccess IPC Implementation: `<https://github.com/galaxyman2424/doclingGraalvm/blob/processbuilder/src/main/java/EmbedPythonTest.java>`
- GraalPy polyglot context test harness: `<https://github.com/galaxyman2424/doclingGraalvm/blob/graalpytester/src/main/java/EmbedPythonTest.java>`
- pom.xml: `<https://github.com/galaxyman2424/doclingGraalvm/blob/graalpytester/pom.xml>`
- Python Resources: `<https://github.com/galaxyman2424/doclingenv/tree/main/lib/python3.12/site-packages>`
- CTypeTest: `<https://github.com/galaxyman2424/doclingGraalvm/blob/CTypeTest/src/main/java/CTypeFuncTest.java>`

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
