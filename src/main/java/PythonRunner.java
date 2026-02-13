import org.graalvm.polyglot.Context;
//import org.graalvm.polyglot.Value;

public class PythonRunner {
    public static void main(String[] args) {
        try (Context context = Context.newBuilder("python")
                .allowAllAccess(true)
                .build()) {
            
            // Execute Python code
            context.eval("python", "print('Hello World')");
        }
    }
}