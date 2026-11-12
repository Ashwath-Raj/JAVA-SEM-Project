import javax.tools.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class CompileAll {
    public static void main(String[] args) throws Exception {
        Path srcDir = Paths.get(args[0]);
        Path outDir = Paths.get(args[1]);
        String classpath = args.length > 2 ? args[2] : "";

        Files.createDirectories(outDir);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("No JavaCompiler available");
            System.exit(1);
        }

        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        List<File> sourceFiles = Files.walk(srcDir)
                .filter(p -> p.toString().endsWith(".java"))
                .map(Path::toFile)
                .collect(Collectors.toList());

        System.out.printf("Compiling %d source files from %s to %s...%n", sourceFiles.size(), srcDir, outDir);

        List<String> options = new ArrayList<>();
        options.add("-d");
        options.add(outDir.toString());
        if (!classpath.isEmpty()) {
            options.add("-cp");
            options.add(classpath);
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(sourceFiles);
        JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits);

        boolean success = task.call();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                System.err.printf("%s at line %d in %s: %s%n",
                        diagnostic.getKind(), diagnostic.getLineNumber(),
                        diagnostic.getSource() != null ? diagnostic.getSource().getName() : "unknown",
                        diagnostic.getMessage(null));
            }
        }

        if (success) {
            System.out.println("COMPILATION SUCCESSFUL: " + outDir);
        } else {
            System.err.println("COMPILATION FAILED");
            System.exit(1);
        }
    }
}
