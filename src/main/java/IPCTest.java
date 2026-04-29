import java.nio.file.Path;
import java.nio.file.Paths;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;

public class IPCTest {

    public static void main(String[] args) {

        // This is the external directory the Maven plugin manages.
        // It creates venv/ and home/ subdirectories inside it automatically.
        Path resourcesDir = Paths.get(System.getProperty("user.dir"), "python-resources");
        Path venvPath     = resourcesDir.resolve("venv");
        Path homePath     = resourcesDir.resolve("home");

        System.out.println("=== Starting GraalPy Docling Test ===");
        System.out.println("Resources dir : " + resourcesDir);
        System.out.println("Venv path     : " + venvPath);

        try (Context context = Context.newBuilder("python")
                .allowAllAccess(true)
                .option("python.Executable",       venvPath.resolve("bin/python").toString())
                .option("python.ExecutableList",   venvPath.resolve("bin/python").toString())
                .option("python.PythonHome",       homePath.toString())
                .option("python.PythonPath",       venvPath.resolve("lib/python3.11/site-packages").toString())
                .option("python.ForceImportSite",  "true")
                .build()) {

            System.out.println("\n[1] Python context created successfully");
            context.eval("python", "import sys; print('Python version:', sys.version)");

            System.out.println("\n[2] Attempting: import docling");
            context.eval("python", """
                try:
                    import docling
                    print("SUCCESS: docling imported, version:", docling.__version__)
                except ImportError as e:
                    print("FAILED - ImportError:", e)
                except Exception as e:
                    print("FAILED -", type(e).__name__, ":", e)
                """);

            System.out.println("\n[3] Attempting: from docling.document_converter import DocumentConverter");
            context.eval("python", """
                try:
                    from docling.document_converter import DocumentConverter
                    print("SUCCESS: DocumentConverter imported!")
                except ImportError as e:
                    print("FAILED - ImportError:", e)
                except Exception as e:
                    print("FAILED -", type(e).__name__, ":", e)
                """);

        } catch (PolyglotException e) {
            System.err.println("\n=== PolyglotException (Python-level error) ===");
            System.err.println(e.getMessage());
        } catch (Exception e) {
            System.err.println("\n=== Java-level error ===");
            e.printStackTrace();
        }
    }
}
