DoclingGraalvm

DoclingGraalvm is a Java project that aims to integrate Docling — an AI-driven document processing and conversion toolkit — with GraalVM, allowing Docling to run efficiently on the GraalVM runtime and take advantage of GraalVM’s polyglot and native execution capabilities.

Note: This repository currently contains minimal code and configuration. The intent is to serve as a starting point for integrating Docling’s APIs within a GraalVM-compatible Java project.

What Is Docling?

Docling is an open-source document understanding and conversion toolkit powered by AI models for layout analysis, table extraction, OCR, and structured content export. It supports PDF, DOCX, XLSX, HTML, images, and more, and integrates with AI frameworks and pipelines.

What Is GraalVM?

GraalVM is an advanced JVM distribution that supports polyglot programming (Java, Python, JavaScript, etc.) and can compile applications ahead-of-time into native executables with reduced startup time and memory footprint.

Project Goals
- Provide a Java wrapper or integration layer to embed Docling functionality.
- Enable Docling to run within a GraalVM environment.
- Explore GraalVM compilation options (native image, polyglot invocation) for document processing tasks.

Requirements:
- Java 21+ 
- GraalVM JDK for java 21 installed on your system
- Maven for building the project

Building
To build the project:
mvn clean install

Running
Depending on the contents of src/main/java, integration examples or entrypoints may be invoked with:
java -jar target/doclingGraalvm.jar

Or executed via GraalVM native image tooling for a compiled binary:

native-image --no-server -jar target/doclingGraalvm.jar

Contributions

This repository is at an early stage. Contributions are welcome to:

Add descriptive APIs for Docling usage

Provide example applications (CLI or server)

Configure GraalVM native image builds

Add tests and documentation
