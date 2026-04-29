import org.graalvm.polyglot.Context;

public class CTypeFuncTest {
    public static void main(String[] args) {

        // ---- Python script: minimal CFUNCTYPE callback ----
        String pythonCode = ""
            + "import ctypes\n"
            + "\n"
            + "# Define a simple callback: int -> int\n"
            + "CALLBACK = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_int)\n"
            + "\n"
            + "def py_callback(x):\n"
            + "    print('Python callback called with:', x)\n"
            + "    return x + 1\n"
            + "\n"
            + "# Wrap Python function as C callback\n"
            + "cb = CALLBACK(py_callback)\n"
            + "\n"
            + "# Call it directly (no native lib, just invoke)\n"
            + "result = cb(41)\n"
            + "print('Callback result:', result)\n";

        // ---- Step 1: Standalone GraalPy (external) ----
        System.out.println("=== EXPECTED: Run this separately in GraalPy ===");
        System.out.println("Save the Python snippet and run with graalpy. It should succeed.\\n");

        // ---- Step 2: Embedded in GraalVM ----
        System.out.println("=== Running inside Java polyglot context ===");

        try (Context context = Context.newBuilder()
                .allowAllAccess(true)
                .build()) {

            context.eval("python", pythonCode);

            System.out.println("\\nSUCCESS: Embedded execution worked.");

        } catch (Exception e) {
            System.out.println("\\nFAILURE: Embedded execution threw exception:");
            e.printStackTrace();
        }
    }
}
