import java.io.File;
import java.io.IOException;
import java.util.Scanner;

public class PythonRunner {
    public static void main(String[] args) {
        // --- CONFIGURATION ---
        String rootPath = "C:/Users/Connor/Desktop/GraalPolyglotProject";
        String pdfPath = rootPath + "/Algo_HW_4.pdf";
        String outputPath = rootPath + "/Algo_HW_4.txt";
        
        // Path to the python executable in your venv
        String venvPythonPath = rootPath + "/docling-env/Scripts/python.exe";

        // Validate prerequisites
        if (!validateSetup(pdfPath, venvPythonPath)) {
            System.err.println("Setup validation failed. Please check paths and files.");
            return;
        }

        // Call native Python subprocess (avoids GraalVM C extension issues)
        try {
            runPythonConversion(venvPythonPath, pdfPath, outputPath);
        } catch (IOException e) {
            System.err.println("✗ File Error: " + e.getMessage());
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.err.println("✗ Process interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Calls native Python via subprocess to convert PDF with Docling
     */
    private static void runPythonConversion(String pythonExe, String pdfPath, String outputPath) 
            throws IOException, InterruptedException {
        
        String pythonScript = 
            "from docling.document_converter import DocumentConverter\n" +
            "import traceback\n" +
            "try:\n" +
            "    converter = DocumentConverter()\n" +
            "    result = converter.convert(r'" + pdfPath + "')\n" +
            "    markdown = result.document.export_to_markdown()\n" +
            "    with open(r'" + outputPath + "', 'w', encoding='utf-8') as f:\n" +
            "        f.write(markdown)\n" +
            "    print('[SUCCESS] Conversion successful!')\n" +
            "    print(f'[SUCCESS] Output saved to: {r\"" + outputPath + "\"}')\n" +
            "except Exception as e:\n" +
            "    print(f'[ERROR] Python Error: {e}')\n" +
            "    traceback.print_exc()\n" +
            "    exit(1)\n";

        System.out.println("Initializing Docling environment...");
        System.out.println("Converting PDF (this may take a moment on the first run)...");

        // Execute Python as subprocess
        ProcessBuilder pb = new ProcessBuilder(pythonExe, "-c", pythonScript);
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        // Capture output
        Scanner scanner = new Scanner(process.getInputStream()).useDelimiter("\\A");
        String output = scanner.hasNext() ? scanner.next() : "";
        
        int exitCode = process.waitFor();
        
        if (output != null && !output.isEmpty()) {
            System.out.println(output);
        }
        
        if (exitCode != 0) {
            System.err.println("✗ Python process exited with code: " + exitCode);
        }
    }

    /**
     * Validates that all required paths and files exist
     */
    private static boolean validateSetup(String pdfPath, String pythonExe) {
        boolean valid = true;

        // Check PDF file
        File pdfFile = new File(pdfPath);
        if (!pdfFile.exists()) {
            System.err.println("✗ PDF file not found: " + pdfPath);
            valid = false;
        } else {
            System.out.println("✓ PDF file found: " + pdfPath);
        }

        // Check Python executable
        File pythonFile = new File(pythonExe);
        if (!pythonFile.exists()) {
            System.err.println("✗ Python executable not found: " + pythonExe);
            valid = false;
        } else {
            System.out.println("✓ Python executable found: " + pythonExe);
        }

        return valid;
    }
}