# Docling on GraalVM (GraalPy Integration)

## 1. Overview

This project embeds IBM Docling directly inside a GraalVM JVM process using GraalPy.

The goal is to eliminate the HTTP/REST layer used by Docling-Serve and instead execute Docling natively within the JVM via GraalVM’s polyglot capabilities.

### Key Objectives
- Replace inter-process communication (REST) with in-process polyglot execution
- Reduce latency and architectural complexity
- Maintain functional parity with Docling-Serve

---

## 2. Architecture

### Baseline (Docling-Serve)
Java → HTTP → Python (Docling) → HTTP → Java

### This Project
Java (GraalVM)
↓
GraalPy (embedded Python runtime)
↓
Docling (native execution)


### Key Insight
Testing confirmed that using `ProcessBuilder` IPC is architecturally equivalent to Docling-Serve with negligible performance difference. However, GraalPy enables a cleaner, single-runtime architecture.

---

## 3. Environment Setup

### System Configuration

- OS: WSL2 Ubuntu
- RAM Allocation:
  - 24GB memory
  - 16GB swap

#### `.wslconfig` (Windows)
[wsl2]
memory=24GB
swap=16GB


⚠️ Apply changes with:

---

### GraalVM Setup

- GraalVM JDK: 21.0.10  
- GraalPy: 25.0.2  

### Virtual Environment
~/docling-env


---

### Required Environment Variables

Run every session:
export JAVA_TOOL_OPTIONS="-Xmx1g -Xms128m -XX:+UseSerialGC"
source ~/docling-env/bin/activate


---

## 4. Dependency Installation

### Important Notes

- `pom.xml` is irrelevant for Python dependency resolution
- Avoid pip spawning subprocess JVMs
- Use GraalVM wheel index

---

### Install Core Dependencies
graalpy -m pip install
numpy==2.2.4
pydantic==2.12.5
pydantic-core==2.41.5


---

### Required Build Dependencies

Ensure these are installed beforehand:

- meson-python
- meson
- ninja
- cython
- versioneer

---

## 5. Pandas Installation (Critical Step)

### Problem

Installing pandas fails due to out-of-memory (OOM) during compilation.

### Root Cause

From `dmesg`:

- `ninja` spawns 15+ parallel Cython jobs
- Each job reserves ~9.3GB virtual memory
- Total demand exceeds system limits
- WSL crashes (not just killing the process)

---

### Solution: Limit Parallelism
export JAVA_TOOL_OPTIONS="-Xmx1g -Xms128m -XX:+UseSerialGC"
export MAX_JOBS=1

graalpy -m pip install pandas
--no-build-isolation
--extra-index-url https://www.graalvm.org/python/wheels/

--config-settings=compile-args=-j1
-v 2>&1 | tee pandas_install.log


---

## 6. GraalPy Compatibility

GraalPy officially supports:

- pandas (via Arrow backend)
- scipy
- torch

This confirms the approach is technically viable.

---

## 7. Current Status

### Working

- numpy 2.2.4
- pydantic 2.12.5
- pydantic-core 2.41.5
- WSL memory properly configured (~23GB available)

### In Progress

- pandas installation (OOM mitigated, not yet completed)

---

## 8. Next Steps

1. Complete pandas installation (single-threaded build)

2. Install Docling dependencies:
pip install docling-parse
pip install docling --no-deps

3. Validate Python:
from docling.document_converter import DocumentConverter

4. Validate Java ↔ Python integration:
- Run `EmbedPythonTest.java`

5. Benchmark vs Docling-Serve

---

## 9. Key Technical Findings

### Confirmed Non-Issues

- LLVM bitcode path
- CPython-in-GraalVM assumptions
- “Assembly-level incompatibility” concerns
- PyArrow workaround attempts
- Custom GraalPy wheel builds

### Critical Discovery

The failure is not compatibility-related.

It is purely:
Parallel compilation memory exhaustion


---

## 10. Development Constraints

- Machine: 32GB RAM
- Windows host has application control restrictions
- Java files located at:
   - C:\Users\Connor\Desktop\GraalPolyglotProject\


---

## 11. Operational Notes

- Every WSL restart requires reinitializing environment variables
- Always use:
  - `--no-build-isolation`
- Memory tuning is required for stability

---

## 12. Project Significance

This project demonstrates:

- Feasibility of running Python data/ML libraries inside a JVM
- Elimination of microservice overhead
- Real-world limits of GraalPy

### Key Takeaway

The primary bottleneck is not runtime compatibility, but build system behavior (ninja + Cython parallelism).

---

## 13. Repository

https://github.com/galaxyman2424/doclingGraalvm/tree/doclingGraalpy
