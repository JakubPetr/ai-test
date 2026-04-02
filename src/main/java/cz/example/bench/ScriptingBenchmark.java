package cz.example.bench;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.introspection.JexlPermissions;
import org.codehaus.janino.ScriptEvaluator;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.h2.jdbcx.JdbcDataSource;
import javax.script.Bindings;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
public class ScriptingBenchmark {
    private static final int ITERATIONS = 1_000;
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--espresso-worker".equals(args[0])) {
            int workerIterations = args.length > 1 ? Integer.parseInt(args[1]) : ITERATIONS;
            runEspressoWorker(workerIterations);
            return;
        }
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : ITERATIONS;
        WorkloadService service = new WorkloadService();
        JdbcDataSource ds = prepareDataSource();
        byte[] zipPayload = createZipPayload();
        List<BenchEngine> engines = List.of(
                new GraalPythonEngine(),
                new GraalEspressoEngine(),
                new GroovyScriptEngineBench(),
                new JaninoBench(),
                new JexlBench()
        );
        List<ResultRow> rows = new ArrayList<>();
        for (BenchEngine engine : engines) {
            rows.add(engine.run(iterations, service, ds, zipPayload));
        }
        System.out.println("\n=== Benchmark (" + iterations + " opakování / use case) ===");
        System.out.printf("%-26s %-14s %-14s %-14s %-12s%n", "Engine", "UC1 avg (µs)", "UC2 avg (µs)", "UC3 avg (µs)", "Status");
        for (ResultRow row : rows) {
            if (row.error != null) {
                System.out.printf("%-26s %-14s %-14s %-14s %-12s%n",
                        row.engineName,
                        "-",
                        "-",
                        "-",
                        "SKIPPED");
                System.out.println("  ↳ " + row.error.getClass().getSimpleName() + ": " + row.error.getMessage());
            } else {
                System.out.printf(Locale.US, "%-26s %-14.2f %-14.2f %-14.2f %-12s%n",
                        row.engineName,
                        nanosToMicros(row.uc1AvgNanos),
                        nanosToMicros(row.uc2AvgNanos),
                        nanosToMicros(row.uc3AvgNanos),
                        "OK");
            }
        }
    }
    private static void runEspressoWorker(int iterations) throws Exception {
        WorkloadService service = new WorkloadService();
        JdbcDataSource ds = prepareDataSource();
        byte[] zipPayload = createZipPayload();
        ResultRow row = executeIterations("espresso-worker", iterations,
                () -> service.shortCheck(42),
                () -> service.mediumTransform(sampleData()),
                () -> service.longProcess(ds, zipPayload));
        System.out.println("ESPRESSO_RESULT:" + row.uc1AvgNanos + "," + row.uc2AvgNanos + "," + row.uc3AvgNanos);
    }
    private static double nanosToMicros(double nanos) {
        return nanos / 1_000.0;
    }
    private static JdbcDataSource prepareDataSource() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:bench;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("sa");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("create table if not exists invoice(id int primary key, amount decimal)");
            s.execute("create table if not exists payment(id identity primary key, invoice_count int, xlsx_count int)");
            s.execute("delete from invoice");
            s.execute("insert into invoice(id, amount) values (1, 100), (2, 200), (3, 350)");
        }
        return ds;
    }
    private static byte[] createZipPayload() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("faktury_01.xlsx"));
            zos.write("dummy".getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("faktury_02.xlsx"));
            zos.write("dummy".getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("notes.txt"));
            zos.write("dummy".getBytes());
            zos.closeEntry();
        }
        return bos.toByteArray();
    }
    interface BenchEngine {
        ResultRow run(int iterations, WorkloadService service, JdbcDataSource ds, byte[] zipPayload);
    }
    static class GraalPythonEngine implements BenchEngine {
        @Override
        public ResultRow run(int iterations, WorkloadService service, JdbcDataSource ds, byte[] zipPayload) {
            try (Context context = Context.newBuilder("python")
                    .allowAllAccess(true)
                    .build()) {
                context.getBindings("python").putMember("svc", service);
                Value uc1Fn = context.eval(Source.newBuilder("python", "def f(age):\n    return svc.shortCheck(age)", "uc1.py").buildLiteral())
                        .getContext().getBindings("python").getMember("f");
                Value uc2Fn = context.eval(Source.newBuilder("python", "def f(data):\n    return svc.mediumTransform(data)", "uc2.py").buildLiteral())
                        .getContext().getBindings("python").getMember("f");
                Value uc3Fn = context.eval(Source.newBuilder("python", "def f(ds, payload):\n    return svc.longProcess(ds, payload)", "uc3.py").buildLiteral())
                        .getContext().getBindings("python").getMember("f");
                return executeIterations("GraalVM Polyglot (Python)", iterations,
                        () -> uc1Fn.execute(42).asBoolean(),
                        () -> uc2Fn.execute(sampleData()).asDouble(),
                        () -> uc3Fn.execute(ds, zipPayload).asInt());
            } catch (Exception ex) {
                return ResultRow.skipped("GraalVM Polyglot (Python)", ex);
            }
        }
    }
    static class GraalEspressoEngine implements BenchEngine {
        @Override
        public ResultRow run(int iterations, WorkloadService service, JdbcDataSource ds, byte[] zipPayload) {
            try {
                String espressoBin = resolveEspressoJavaBinary();
                if (espressoBin == null) {
                    return ResultRow.skipped("GraalVM Espresso (standalone)",
                            new IllegalStateException("Nenalezen espresso launcher. Nastav ESPRESSO_JAVA_BIN na path k Espresso standalone java binárce."));
                }
                ProcessBuilder pb = new ProcessBuilder(
                        espressoBin,
                        "-truffle",
                        "-cp",
                        System.getProperty("java.class.path"),
                        ScriptingBenchmark.class.getName(),
                        "--espresso-worker",
                        String.valueOf(iterations)
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();
                String line;
                String resultLine = null;
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append(System.lineSeparator());
                        if (line.startsWith("ESPRESSO_RESULT:")) {
                            resultLine = line;
                        }
                    }
                }
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new IllegalStateException("Espresso worker skončil s code " + exitCode + "\n" + output);
                }
                if (resultLine == null) {
                    throw new IllegalStateException("Espresso worker nevrátil výsledek. Output:\n" + output);
                }
                String csv = resultLine.substring("ESPRESSO_RESULT:".length());
                String[] parts = csv.split(",");
                return new ResultRow("GraalVM Espresso (standalone)",
                        Double.parseDouble(parts[0]),
                        Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]),
                        null);
            } catch (Exception ex) {
                return ResultRow.skipped("GraalVM Espresso (standalone)", ex);
            }
        }
        private String resolveEspressoJavaBinary() {
            String fromEnv = System.getenv("ESPRESSO_JAVA_BIN");
            if (fromEnv != null && !fromEnv.isBlank()) {
                return fromEnv;
            }
            return null;
        }
    }
    static class GroovyScriptEngineBench implements BenchEngine {
        @Override
        public ResultRow run(int iterations, WorkloadService service, JdbcDataSource ds, byte[] zipPayload) {
            try {
                ScriptEngine engine = new ScriptEngineManager().getEngineByName("groovy");
                Compilable compilable = (Compilable) engine;
                CompiledScript uc1 = compilable.compile("svc.shortCheck(age)");
                CompiledScript uc2 = compilable.compile("svc.mediumTransform(data)");
                CompiledScript uc3 = compilable.compile("svc.longProcess(ds, payload)");
                return executeIterations("Groovy (JSR-223)", iterations,
                        () -> (Boolean) uc1.eval(bindings(engine, service, 42, sampleData(), ds, zipPayload)),
                        () -> ((Number) uc2.eval(bindings(engine, service, 42, sampleData(), ds, zipPayload))).doubleValue(),
                        () -> ((Number) uc3.eval(bindings(engine, service, 42, sampleData(), ds, zipPayload))).intValue());
            } catch (Exception ex) {
                return ResultRow.skipped("Groovy (JSR-223)", ex);
            }
        }
        private Bindings bindings(ScriptEngine engine, WorkloadService service, int age, Map<String, Object> data,
                                  JdbcDataSource ds, byte[] payload) {
            Bindings b = engine.createBindings();
            b.put("svc", service);
            b.put("age", age);
            b.put("data", data);
            b.put("ds", ds);
            b.put("payload", payload);
            return b;
        }
    }
    static class JaninoBench implements BenchEngine {
        @Override
        public ResultRow run(int iterations, WorkloadService service, JdbcDataSource ds, byte[] zipPayload) {
            try {
                ScriptEvaluator uc1 = new ScriptEvaluator(
                        "return svc.shortCheck(age);",
                        boolean.class,
                        new String[]{"svc", "age"},
                        new Class[]{WorkloadService.class, int.class}
                );
                ScriptEvaluator uc2 = new ScriptEvaluator(
                        "return svc.mediumTransform(data);",
                        double.class,
                        new String[]{"svc", "data"},
                        new Class[]{WorkloadService.class, Map.class}
                );
                ScriptEvaluator uc3 = new ScriptEvaluator(
                        "return svc.longProcess(ds, payload);",
                        int.class,
                        new String[]{"svc", "ds", "payload"},
                        new Class[]{WorkloadService.class, javax.sql.DataSource.class, byte[].class}
                );
                return executeIterations("Janino", iterations,
                        () -> (Boolean) uc1.evaluate(new Object[]{service, 42}),
                        () -> ((Number) uc2.evaluate(new Object[]{service, sampleData()})).doubleValue(),
                        () -> ((Number) uc3.evaluate(new Object[]{service, ds, zipPayload})).intValue());
            } catch (Exception ex) {
                return ResultRow.skipped("Janino", ex);
            }
        }
    }
    static class JexlBench implements BenchEngine {
        @Override
        public ResultRow run(int iterations, WorkloadService service, JdbcDataSource ds, byte[] zipPayload) {
            try {
                JexlEngine jexl = new JexlBuilder().cache(128).strict(true).permissions(JexlPermissions.UNRESTRICTED).create();
                JexlScript uc1 = jexl.createScript("svc.shortCheck(age)");
                JexlScript uc2 = jexl.createScript("svc.mediumTransform(data)");
                JexlScript uc3 = jexl.createScript("svc.longProcess(ds, payload)");
                return executeIterations("Apache JEXL", iterations,
                        () -> (Boolean) uc1.execute(MapContext.of(service, 42, sampleData(), ds, zipPayload)),
                        () -> ((Number) uc2.execute(MapContext.of(service, 42, sampleData(), ds, zipPayload))).doubleValue(),
                        () -> ((Number) uc3.execute(MapContext.of(service, 42, sampleData(), ds, zipPayload))).intValue());
            } catch (Exception ex) {
                return ResultRow.skipped("Apache JEXL", ex);
            }
        }
    }
    private static ResultRow executeIterations(String engineName,
                                               int iterations,
                                               ThrowingSupplier<Boolean> uc1,
                                               ThrowingSupplier<Double> uc2,
                                               ThrowingSupplier<Integer> uc3) throws Exception {
        for (int i = 0; i < 50; i++) {
            uc1.get();
            uc2.get();
            uc3.get();
        }
        long uc1Total = 0;
        long uc2Total = 0;
        long uc3Total = 0;
        for (int i = 0; i < iterations; i++) {
            long t1 = System.nanoTime();
            uc1.get();
            uc1Total += System.nanoTime() - t1;
            long t2 = System.nanoTime();
            uc2.get();
            uc2Total += System.nanoTime() - t2;
            long t3 = System.nanoTime();
            uc3.get();
            uc3Total += System.nanoTime() - t3;
        }
        return new ResultRow(engineName,
                uc1Total / (double) iterations,
                uc2Total / (double) iterations,
                uc3Total / (double) iterations,
                null);
    }
    private static Map<String, Object> sampleData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("net", 1250.0);
        data.put("vatRate", 0.21);
        data.put("customerId", "A-123");
        return data;
    }
    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
    static class ResultRow {
        final String engineName;
        final double uc1AvgNanos;
        final double uc2AvgNanos;
        final double uc3AvgNanos;
        final Exception error;
        ResultRow(String engineName, double uc1AvgNanos, double uc2AvgNanos, double uc3AvgNanos, Exception error) {
            this.engineName = engineName;
            this.uc1AvgNanos = uc1AvgNanos;
            this.uc2AvgNanos = uc2AvgNanos;
            this.uc3AvgNanos = uc3AvgNanos;
            this.error = error;
        }
        static ResultRow skipped(String engineName, Exception ex) {
            return new ResultRow(engineName, 0, 0, 0, ex);
        }
    }
    public static class WorkloadService {
        public boolean shortCheck(int age) {
            return age >= 18 && age <= 65 && (age % 2 == 0);
        }
        public double mediumTransform(Map<String, Object> data) {
            double net = ((Number) data.get("net")).doubleValue();
            double vatRate = ((Number) data.get("vatRate")).doubleValue();
            String customerId = String.valueOf(data.get("customerId"));
            if (customerId.isBlank()) {
                throw new IllegalArgumentException("customerId cannot be blank");
            }
            return Math.round((net * (1 + vatRate)) * 100.0d) / 100.0d;
        }
        public int longProcess(javax.sql.DataSource dataSource, byte[] payload) {
            int invoiceCount = 0;
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                try (ResultSet rs = s.executeQuery("select count(*) from invoice")) {
                    if (rs.next()) {
                        invoiceCount = rs.getInt(1);
                    }
                }
                int xlsxCount = countXlsxEntries(payload);
                s.execute("insert into payment(invoice_count, xlsx_count) values (" + invoiceCount + ", " + xlsxCount + ")");
                return invoiceCount + xlsxCount;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        private int countXlsxEntries(byte[] payload) throws IOException {
            int count = 0;
            try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(payload))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                        count++;
                    }
                }
            }
            return count;
        }
    }
    static class MapContext extends org.apache.commons.jexl3.MapContext {
        private MapContext(Map<String, Object> data) {
            super(data);
        }
        static MapContext of(WorkloadService svc, int age, Map<String, Object> data, JdbcDataSource ds, byte[] payload) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("svc", svc);
            vars.put("age", age);
            vars.put("data", data);
            vars.put("ds", ds);
            vars.put("payload", payload);
            return new MapContext(vars);
        }
    }
}
