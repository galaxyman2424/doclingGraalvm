import org.graalvm.polyglot.Context;
import java.nio.file.Paths;

public class EmbedPythonTest {
    public static void main(String[] args) {
        System.out.println("=== Testing Docling in GraalPy ===\n");

        String venvPath = Paths.get(System.getProperty("user.home"), "docling-env-new").toString();
        String sitePkgsPath = venvPath + "/lib/python3.12/site-packages";

        try (Context context = Context.newBuilder("python")
                .allowAllAccess(true)
                .option("python.PythonPath", sitePkgsPath)
                .build()) {

            System.out.println("[1] Testing basic import...");
            context.eval("python", "import sys; print('Python version:', sys.version)");

            System.out.println("\n[2] Testing Docling import...");
            context.eval("python", "from docling.document_converter import DocumentConverter; print('✓ DocumentConverter imported')");

            System.out.println("\n[3] Testing PDF conversion...");
            String conversionCode = 
                "converter = DocumentConverter()\n" +
                "result = converter.convert('/home/connor/doclingproject/Files4Docling/Algo_HW_4.pdf')\n" +
                "print(f'✓ PDF conversion successful: {len(result.document.pages)} pages')";
            
            context.eval("python", conversionCode);

            System.out.println("\n=== SUCCESS ===");

        } catch (Exception e) {
            System.err.println("\n✗ ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
