Overview

This project demonstrates how to embed Python inside a Java 17 application using GraalVM’s Polyglot runtime (GraalPy). The objective is to allow Java code to execute Python logic directly within the same process, enabling interoperability between the two languages without spawning external Python processes.

The motivation for this setup is:

Maintain a Java-based system (required constraint: Java 17)

Execute Python code for scripting, data processing, or AI-related workflows

Avoid traditional approaches such as subprocess execution or REST bridges

Use GraalVM’s polyglot capabilities for native language interoperability

Architecture Concept

The system uses:

Java (JDK 17) — primary application runtime

GraalVM Polyglot API — language interoperability layer

GraalPy — Python implementation running on GraalVM

Maven — project build and dependency management

Execution flow:

Java Application
       ↓
GraalVM Polyglot Context
       ↓
Embedded GraalPy Runtime
       ↓
Python Code Execution

Java creates a Context object that loads the Python engine and executes Python scripts directly.

Project Goals

Embed Python execution inside a Java application.

Ensure compatibility with Java 17 constraints.

Run Python without installing or invoking system CPython.

Maintain a clean Maven-based build workflow.

Establish a foundation for future Java ↔ Python interoperability.

What Has Been Accomplished
Environment Setup

Installed Java 17 JDK (project constraint)
Installed GraalVM distribution compatible with Java 17
Downloaded and configured GraalPy runtime
Verified GraalVM tooling works locally

Project Configuration

Created Maven project structure:

project-root/
 ├─ pom.xml
 └─ src/main/java/
     └─ PythonRunner.java

Configured Maven dependencies for:

GraalVM Polyglot API

Python language support

Configured exec-maven-plugin to run a Java main class.

Embedding Python

Implemented a Java entry class (PythonRunner) that:

Creates a GraalVM polyglot context

Enables Python execution

Runs embedded Python code

Example concept:

Context context = Context.newBuilder("python")
    .allowAllAccess(true)
    .build();

context.eval("python", "print('Hello from Python')");

This confirms Python execution inside the JVM.

Toolchain Decisions
Decision	Reason
Java 17 only	Course/environment constraint
GraalPy instead of CPython	Native JVM integration
Embedded runtime	No subprocess overhead
Maven	Standardized builds
Current State

The project now has:

A working GraalPy installation

Java ↔ Python execution capability

Maven build configuration

Main class located in the default package

Execution via:

mvn compile
mvn exec:java

At this stage, Python code can successfully run from Java.
