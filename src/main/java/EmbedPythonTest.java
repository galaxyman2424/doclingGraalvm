import org.graalvm.polyglot.*;

public class EmbedPythonTest {
    public static void main(String[] args) {
        // Create a polyglot context
        try (Context context = Context.newBuilder().allowAllAccess(true).build()) {
            // Execute Python code
            context.eval("python", "print('Hello from GraalPython!')");
        }
    }
}