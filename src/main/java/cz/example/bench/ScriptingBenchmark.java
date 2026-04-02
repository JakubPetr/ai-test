package cz.example.bench;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.introspection.JexlPermissions;
import org.codehaus.janino.ScriptEvaluator;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import bsh.Interpreter;
import org.h2.jdbcx.JdbcDataSource;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        JdbcDataSource ds = prepareDataSource();
        byte[] zipPayload = createZipPayload();

        List<BenchEngine> engines = List.of(
                new GraalPythonEngine(),
                new GraalEspressoEngine(),
                new GroovyScriptEngineBench(),
                new JaninoBench(),
                new JexlBench(),
                new GroovyShellBench(),
                new BeanShellBench()
        );

        List<ResultRow> rows = new ArrayList<>();
        for (BenchEngine engine : engines) {
            rows.add(engine.run(iterations, ds, zipPayload));
        }

        System.out.println("\n=== Benchmark (" + iterations + " opakování / use case) ===");
        System.out.printf("%-30s %-14s %-14s %-14s %-12s%n", "Engine", "UC1 avg (µs)", "UC2 avg (µs)", "UC3 avg (µs)", "Status");
        for (ResultRow row : rows) {
            if (row.error != null) {
                System.out.printf("%-30s %-14s %-14s %-14s %-12s%n", row.engineName, "-", "-", "-", "SKIPPED");
                System.out.println("  ↳ " + row.error.getClass().getSimpleName() + ": " + row.error.getMessage());
            } else {
                System.out.printf(Locale.US, "%-30s %-14.2f %-14.2f %-14.2f %-12s%n",
                        row.engineName,
                        nanosToMicros(row.uc1AvgNanos),
                        nanosToMicros(row.uc2AvgNanos),
                        nanosToMicros(row.uc3AvgNanos),
                        "OK");
            }
        }
    }

    private static void runEspressoWorker(int iterations) throws Exception {
        JdbcDataSource ds = prepareDataSource();
        byte[] zipPayload = createZipPayload();

        EspressoCompiled compiled = compileEspressoScript();

        ResultRow row = executeIterations("espresso-worker", iterations,
                () -> (Boolean) compiled.uc1.invoke(null, 42),
                () -> ((Number) compiled.uc2.invoke(null, sampleData())).doubleValue(),
                () -> ((Number) compiled.uc3.invoke(null, ds, zipPayload)).intValue());

        System.out.println("ESPRESSO_RESULT:" + row.uc1AvgNanos + "," + row.uc2AvgNanos + "," + row.uc3AvgNanos);
    }

    private static EspressoCompiled compileEspressoScript() throws Exception {
        String src = """
                import java.sql.*;
                import java.util.*;
                import java.util.zip.*;
                import java.io.*;

                public class EspressoRules {
                    public static boolean uc1(int age) {
                        boolean adult = age >= 18;
                        boolean notSenior = age <= 65;
                        boolean even = age % 2 == 0;
                        return adult && notSenior && even;
                    }

                    public static double uc2(Map<String, Object> data) {
                        double net = ((Number) data.get("net")).doubleValue();
                        double vatRate = ((Number) data.get("vatRate")).doubleValue();
                        String customer = String.valueOf(data.get("customerId"));
                        if (customer == null || customer.isBlank()) throw new IllegalArgumentException("missing customer");
                        double gross = net * (1.0 + vatRate);
                        double fee = gross > 1500.0 ? 14.5 : 5.0;
                        double discount = customer.startsWith("A-") ? 0.03 : 0.0;
                        return Math.round((gross + fee - gross * discount) * 100.0) / 100.0;
                    }

                    public static int uc3(javax.sql.DataSource ds, byte[] payload) throws Exception {
                        int invoiceCount = 0;
                        int xlsxCount = 0;
                        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                            try (ResultSet rs = s.executeQuery("select count(*) from invoice")) {
                                if (rs.next()) invoiceCount = rs.getInt(1);
                            }
                            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(payload))) {
                                ZipEntry entry;
                                while ((entry = zis.getNextEntry()) != null) {
                                    String name = entry.getName().toLowerCase(Locale.ROOT);
                                    if (name.endsWith(".xlsx")) xlsxCount++;
                                }
                            }
                            s.execute("insert into payment(invoice_count, xlsx_count) values (" + invoiceCount + "," + xlsxCount + ")");
                        }
                        return invoiceCount + xlsxCount;
                    }
                }
                """;

        Path dir = Files.createTempDirectory("espresso-rules");
        Path javaFile = dir.resolve("EspressoRules.java");
        Files.writeString(javaFile, src, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JavaCompiler není dostupný");
        }
        int code = compiler.run(null, null, null, javaFile.toString());
        if (code != 0) {
            throw new IllegalStateException("Kompilace EspressoRules selhala: " + code);
        }

        try (URLClassLoader cl = new URLClassLoader(new URL[]{dir.toUri().toURL()})) {
            Class<?> rules = cl.loadClass("EspressoRules");
            return new EspressoCompiled(
                    rules.getMethod("uc1", int.class),
                    rules.getMethod("uc2", Map.class),
                    rules.getMethod("uc3", javax.sql.DataSource.class, byte[].class)
            );
        }
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
            s.execute("delete from payment");
            s.execute("insert into invoice(id, amount) values (1, 100), (2, 200), (3, 350)");
        }

        return ds;
    }

    private static byte[] createZipPayload() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("faktury_01.xlsx"));
            zos.write("dummy".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("faktury_02.xlsx"));
            zos.write("dummy".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("notes.txt"));
            zos.write("dummy".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private static Map<String, Object> sampleData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("net", 1250.0);
        data.put("vatRate", 0.21);
        data.put("customerId", "A-123");
        return data;
    }

    interface BenchEngine {
        ResultRow run(int iterations, JdbcDataSource ds, byte[] zipPayload);
    }

    static class GraalPythonEngine implements BenchEngine {
        @Override
        public ResultRow run(int iterations, JdbcDataSource ds, byte[] zipPayload) {
            String uc1Script = """
                    def f(age):
                        adult = age >= 18
                        not_senior = age <= 65
                        even = age % 2 == 0
                        return adult and not_senior and even
                    """;

            String uc2Script = """
                    def f(data):
                        net = float(data.get('net'))
                        vat_rate = float(data.get('vatRate'))
                        customer = str(data.get('customerId'))
                        if customer is None or len(customer.strip()) == 0:
                            raise ValueError('missing customerId')
                        gross = net * (1.0 + vat_rate)
                        fee = 14.5 if gross > 1500.0 else 5.0
                        discount = 0.03 if customer.startswith('A-') else 0.0
                        result = gross + fee - (gross * discount)
                        return round(result, 2)
                    """;

            String uc3Script = """
                    def f(ds, payload, byte_array_in, zip_in):
                        conn = ds.getConnection()
                        try:
                            st = conn.createStatement()
                            rs = st.executeQuery('select count(*) from invoice')
                            invoice_count = 0
                            if rs.next():
                                invoice_count = rs.getInt(1)
                            rs.close()

                            zis = zip_in(byte_array_in(payload))
                            xlsx_count = 0
                            entry = zis.getNextEntry()
                            while entry is not None:
                                name = entry.getName().lower()
                                if name.endswith('.xlsx'):
                                    xlsx_count += 1
                                entry = zis.getNextEntry()
                            zis.close()

                            st.execute('insert into payment(invoice_count, xlsx_count) values (' + str(invoice_count) + ', ' + str(xlsx_count) + ')')
                            st.close()
                            return invoice_count + xlsx_count
                        finally:
                            conn.close()
                    """;

            try (Context context = Context.newBuilder("python").allowAllAccess(true).build()) {
                context.getBindings("python").putMember("ByteArrayInputStream", ByteArrayInputStream.class);
                context.getBindings("python").putMember("ZipInputStream", ZipInputStream.class);

                context.eval(Source.newBuilder("python", uc1Script, "uc1.py").buildLiteral());
                Value uc1 = context.getBindings("python").getMember("f");

                context.eval(Source.newBuilder("python", uc2Script, "uc2.py").buildLiteral());
                Value uc2 = context.getBindings("python").getMember("f");

                context.eval(Source.newBuilder("python", uc3Script, "uc3.py").buildLiteral());
                Value uc3 = context.getBindings("python").getMember("f");

                return executeIterations("GraalVM Polyglot (Python)", iterations,
                        () -> uc1.execute(42).asBoolean(),
                        () -> uc2.execute(sampleData()).asDouble(),
                        () -> uc3.execute(ds, zipPayload, ByteArrayInputStream.class, ZipInputStream.class).asInt());
            } catch (Exception ex) {
                return ResultRow.skipped("GraalVM Polyglot (Python)", ex);
            }
        }
    }

    static class GraalEspressoEngine implements BenchEngine {
        @Override
        public ResultRow run(int iterations, JdbcDataSource ds, byte[] zipPayload) {
            try {
                String espressoBin = System.getenv("ESPRESSO_JAVA_BIN");
                if (espressoBin == null || espressoBin.isBlank()) {
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

                String[] parts = resultLine.substring("ESPRESSO_RESULT:".length()).split(",");
                return new ResultRow("GraalVM Espresso (standalone)",
                        Double.parseDouble(parts[0]),
                        Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]),
                        null);
            } catch (Exception ex) {
                return ResultRow.skipped("GraalVM Espresso (standalone)", ex);
            }
        }
    }

    static class GroovyScriptEngineBench implements BenchEngine {
        @Override
        public ResultRow run(int iterations, JdbcDataSource ds, byte[] zipPayload) {
            if (Runtime.version().feature() >= 25) {
                return ResultRow.skipped("Groovy (JSR-223)", new IllegalStateException("Groovy 3.x není kompatibilní s Java 25 class version v tomto prostředí."));
            }

            String uc1Code = """
                    def adult = age >= 18
                    def notSenior = age <= 65
                    def even = (age % 2) == 0
                    return adult && notSenior && even
                    """;

            String uc2Code = """
                    double net = ((Number)data.net).doubleValue()
                    double vatRate = ((Number)data.vatRate).doubleValue()
                    String customer = String.valueOf(data.customerId)
                    if (customer == null || customer.isBlank()) throw new IllegalArgumentException('missing customerId')
                    double gross = net * (1.0 + vatRate)
                    double fee = gross > 1500.0 ? 14.5 : 5.0
                    double discount = customer.startsWith('A-') ? 0.03 : 0.0
                    return Math.round((gross + fee - gross * discount) * 100.0d) / 100.0d
                    """;

            String uc3Code = """
                    def conn = ds.getConnection()
                    try {
                        def st = conn.createStatement()
                        def rs = st.executeQuery('select count(*) from invoice')
                        int invoiceCount = 0
                        if (rs.next()) {
                            invoiceCount = rs.getInt(1)
                        }
                        rs.close()

                        int xlsxCount = 0
                        def zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(payload))
                        try {
                            def entry = zis.getNextEntry()
                            while (entry != null) {
                                if (entry.getName().toLowerCase(Locale.ROOT).endsWith('.xlsx')) {
                                    xlsxCount++
                                }
                                entry = zis.getNextEntry()
                            }
                        } finally {
                            zis.close()
                        }

                        st.execute("insert into payment(invoice_count, xlsx_count) values (${invoiceCount}, ${xlsxCount})")
                        st.close()
                        return invoiceCount + xlsxCount
                    } finally {
                        conn.close()
                    }
                    """;

            try {
                ScriptEngine engine = new ScriptEngineManager().getEngineByName("groovy");
                Compilable compilable = (Compilable) engine;
                CompiledScript uc1 = compilable.compile(uc1Code);
                CompiledScript uc2 = compilable.compile(uc2Code);
                CompiledScript uc3 = compilable.compile(uc3Code);

                return executeIterations("Groovy (JSR-223)", iterations,
                        () -> (Boolean) uc1.eval(bindings(engine, 42, sampleData(), ds, zipPayload)),
                        () -> ((Number) uc2.eval(bindings(engine, 42, sampleData(), ds, zipPayload))).doubleValue(),
                        () -> ((Number) uc3.eval(bindings(engine, 42, sampleData(), ds, zipPayload))).intValue());
            } catch (Throwable ex) {
                return ResultRow.skipped("Groovy (JSR-223)", new RuntimeException(ex));
            }
        }

        private Bindings bindings(ScriptEngine engine, int age, Map<String, Object> data,
                                  JdbcDataSource ds, byte[] payload) {
            Bindings b = engine.createBindings();
            b.put("age", age);
            b.put("data", data);
            b.put("ds", ds);
            b.put("payload", payload);
            b.put("Locale", Locale.class);
            return b;
        }
    }

    static class JaninoBench implements BenchEngine {
        @Override
        public ResultRow run(int iterations, JdbcDataSource ds, byte[] zipPayload) {
            try {
                ScriptEvaluator uc1 = new ScriptEvaluator(
                        "boolean adult = age >= 18; boolean notSenior = age <= 65; boolean even = (age % 2) == 0; return adult && notSenior && even;",
                        boolean.class,
                        new String[]{"age"},
                        new Class[]{int.class}
                );

                ScriptEvaluator uc2 = new ScriptEvaluator(
                        "double net = ((Number)data.get(\"net\")).doubleValue();"
                                + "double vatRate = ((Number)data.get(\"vatRate\")).doubleValue();"
                                + "String customer = String.valueOf(data.get(\"customerId\"));"
                                + "if (customer == null || customer.isBlank()) throw new IllegalArgumentException(\"missing customerId\");"
                                + "double gross = net * (1.0 + vatRate);"
                                + "double fee = gross > 1500.0 ? 14.5 : 5.0;"
                                + "double discount = customer.startsWith(\"A-\") ? 0.03 : 0.0;"
                                + "return Math.round((gross + fee - gross * discount) * 100.0d) / 100.0d;",
                        double.class,
                        new String[]{"data"},
                        new Class[]{Map.class}
                );

                ScriptEvaluator uc3 = new ScriptEvaluator(
                        "int invoiceCount = 0; int xlsxCount = 0;"
                                + "java.sql.Connection c = null; java.sql.Statement st = null; java.sql.ResultSet rs = null; java.util.zip.ZipInputStream zis = null;"
                                + "try {"
                                + "  c = ds.getConnection(); st = c.createStatement(); rs = st.executeQuery(\"select count(*) from invoice\");"
                                + "  if (rs.next()) invoiceCount = rs.getInt(1);"
                                + "  zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(payload));"
                                + "  java.util.zip.ZipEntry entry;"
                                + "  while ((entry = zis.getNextEntry()) != null) {"
                                + "    String name = entry.getName().toLowerCase(java.util.Locale.ROOT);"
                                + "    if (name.endsWith(\".xlsx\")) xlsxCount++;"
                                + "  }"
                                + "  st.execute(\"insert into payment(invoice_count, xlsx_count) values (\" + invoiceCount + \",\" + xlsxCount + \")\");"
                                + "} catch (Exception e) { throw new RuntimeException(e); }"
                                + "finally { try { if (rs != null) rs.close(); } catch (Exception e) {}"
                                + "         try { if (zis != null) zis.close(); } catch (Exception e) {}"
                                + "         try { if (st != null) st.close(); } catch (Exception e) {}"
                                + "         try { if (c != null) c.close(); } catch (Exception e) {} }"
                                + "return invoiceCount + xlsxCount;",
                        int.class,
                        new String[]{"ds", "payload"},
                        new Class[]{javax.sql.DataSource.class, byte[].class}
                );

                return executeIterations("Janino", iterations,
                        () -> (Boolean) uc1.evaluate(new Object[]{42}),
                        () -> ((Number) uc2.evaluate(new Object[]{sampleData()})).doubleValue(),
                        () -> ((Number) uc3.evaluate(new Object[]{ds, zipPayload})).intValue());
            } catch (Exception ex) {
                return ResultRow.skipped("Janino", ex);
            }
        }
    }

    static class JexlBench implements BenchEngine {
        @Override
        public ResultRow run(int iterations, JdbcDataSource ds, byte[] zipPayload) {
            try {
                JexlEngine jexl = new JexlBuilder().cache(128).strict(true).permissions(JexlPermissions.UNRESTRICTED).create();

                JexlScript uc1 = jexl.createScript("var adult = age >= 18; var notSenior = age <= 65; var even = (age % 2) == 0; adult && notSenior && even");
                JexlScript uc2 = jexl.createScript(""
                        + "var net=((data.net)); "
                        + "var vat=((data.vatRate)); "
                        + "var customer=''+data.customerId; "
                        + "if (customer.trim().isEmpty()) { throw('missing customerId'); } "
                        + "var gross = net * (1.0 + vat); "
                        + "var fee = gross > 1500.0 ? 14.5 : 5.0; "
                        + "var discount = customer.startsWith('A-') ? 0.03 : 0.0; "
                        + "Math.round((gross + fee - gross * discount) * 100.0d) / 100.0d");

                JexlScript uc3 = jexl.createScript(""
                        + "var conn = ds.getConnection(); "
                        + "var st = conn.createStatement(); "
                        + "var rs = st.executeQuery('select count(*) from invoice'); "
                        + "var invoiceCount = 0; "
                        + "if (rs.next()) { invoiceCount = rs.getInt(1); } "
                        + "rs.close(); "
                        + "var zis = new('java.util.zip.ZipInputStream', new('java.io.ByteArrayInputStream', payload)); "
                        + "var entry = zis.getNextEntry(); "
                        + "var xlsxCount = 0; "
                        + "while (entry != null) { var name = entry.getName().toLowerCase(); if (name.endsWith('.xlsx')) { xlsxCount = xlsxCount + 1; } entry = zis.getNextEntry(); } "
                        + "zis.close(); "
                        + "st.execute('insert into payment(invoice_count, xlsx_count) values (' + invoiceCount + ',' + xlsxCount + ')'); "
                        + "st.close(); conn.close(); invoiceCount + xlsxCount");

                return executeIterations("Apache JEXL", iterations,
                        () -> (Boolean) uc1.execute(MapContext.of(42, sampleData(), ds, zipPayload)),
                        () -> ((Number) uc2.execute(MapContext.of(42, sampleData(), ds, zipPayload))).doubleValue(),
                        () -> ((Number) uc3.execute(MapContext.of(42, sampleData(), ds, zipPayload))).intValue());
            } catch (Exception ex) {
                return ResultRow.skipped("Apache JEXL", ex);
            }
        }
    }


    static class GroovyShellBench implements BenchEngine {
        @Override
        public ResultRow run(int iterations, JdbcDataSource ds, byte[] zipPayload) {
            if (Runtime.version().feature() >= 25) {
                return ResultRow.skipped("GroovyShell", new IllegalStateException("Groovy 3.x není kompatibilní s Java 25 class version v tomto prostředí."));
            }
            try {
                GroovyShell shell = new GroovyShell();
                Script uc1 = shell.parse("""
                        def run(age) {
                            def adult = age >= 18
                            def notSenior = age <= 65
                            def even = (age % 2) == 0
                            return adult && notSenior && even
                        }
                        """);
                Script uc2 = shell.parse("""
                        def run(data) {
                            double net = ((Number)data.net).doubleValue()
                            double vatRate = ((Number)data.vatRate).doubleValue()
                            String customer = String.valueOf(data.customerId)
                            if (customer == null || customer.isBlank()) throw new IllegalArgumentException('missing customerId')
                            double gross = net * (1.0 + vatRate)
                            double fee = gross > 1500.0 ? 14.5 : 5.0
                            double discount = customer.startsWith('A-') ? 0.03 : 0.0
                            return Math.round((gross + fee - gross * discount) * 100.0d) / 100.0d
                        }
                        """);
                Script uc3 = shell.parse("""
                        def run(ds, payload) {
                            def conn = ds.getConnection()
                            try {
                                def st = conn.createStatement()
                                def rs = st.executeQuery('select count(*) from invoice')
                                int invoiceCount = 0
                                if (rs.next()) {
                                    invoiceCount = rs.getInt(1)
                                }
                                rs.close()
                                int xlsxCount = 0
                                def zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(payload))
                                try {
                                    def entry = zis.getNextEntry()
                                    while (entry != null) {
                                        if (entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith('.xlsx')) {
                                            xlsxCount++
                                        }
                                        entry = zis.getNextEntry()
                                    }
                                } finally {
                                    zis.close()
                                }
                                st.execute("insert into payment(invoice_count, xlsx_count) values (${invoiceCount}, ${xlsxCount})")
                                st.close()
                                return invoiceCount + xlsxCount
                            } finally {
                                conn.close()
                            }
                        }
                        """);

                return executeIterations("GroovyShell", iterations,
                        () -> (Boolean) uc1.invokeMethod("run", new Object[]{42}),
                        () -> ((Number) uc2.invokeMethod("run", new Object[]{sampleData()})).doubleValue(),
                        () -> ((Number) uc3.invokeMethod("run", new Object[]{ds, zipPayload})).intValue());
            } catch (Throwable ex) {
                return ResultRow.skipped("GroovyShell", new RuntimeException(ex));
            }
        }
    }

    static class BeanShellBench implements BenchEngine {
        @Override
        public ResultRow run(int iterations, JdbcDataSource ds, byte[] zipPayload) {
            try {
                Interpreter interpreter = new Interpreter();
                interpreter.eval("""
                        boolean uc1(int age) {
                            boolean adult = age >= 18;
                            boolean notSenior = age <= 65;
                            boolean even = (age % 2) == 0;
                            return adult && notSenior && even;
                        }
                        """);
                interpreter.eval("""
                        double uc2(java.util.Map data) {
                            double net = ((Number)data.get("net")).doubleValue();
                            double vatRate = ((Number)data.get("vatRate")).doubleValue();
                            String customer = String.valueOf(data.get("customerId"));
                            if (customer == null || customer.trim().isEmpty()) throw new IllegalArgumentException("missing customerId");
                            double gross = net * (1.0 + vatRate);
                            double fee = gross > 1500.0 ? 14.5 : 5.0;
                            double discount = customer.startsWith("A-") ? 0.03 : 0.0;
                            return Math.round((gross + fee - gross * discount) * 100.0d) / 100.0d;
                        }
                        """);
                interpreter.eval("""
                        int uc3(javax.sql.DataSource ds, byte[] payload) {
                            java.sql.Connection c = null;
                            java.sql.Statement st = null;
                            java.sql.ResultSet rs = null;
                            java.util.zip.ZipInputStream zis = null;
                            int result = 0;
                            try {
                                int invoiceCount = 0;
                                int xlsxCount = 0;
                                c = ds.getConnection();
                                st = c.createStatement();
                                rs = st.executeQuery("select count(*) from invoice");
                                if (rs.next()) {
                                    invoiceCount = rs.getInt(1);
                                }
                                zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(payload));
                                java.util.zip.ZipEntry entry;
                                while ((entry = zis.getNextEntry()) != null) {
                                    String name = entry.getName().toLowerCase(java.util.Locale.ROOT);
                                    if (name.endsWith(".xlsx")) {
                                        xlsxCount++;
                                    }
                                }
                                st.execute("insert into payment(invoice_count, xlsx_count) values (" + invoiceCount + "," + xlsxCount + ")");
                                result = invoiceCount + xlsxCount;
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                try { if (rs != null) rs.close(); } catch (Exception e) {}
                                try { if (zis != null) zis.close(); } catch (Exception e) {}
                                try { if (st != null) st.close(); } catch (Exception e) {}
                                try { if (c != null) c.close(); } catch (Exception e) {}
                            }
                            return result;
                        }
                        """);

                return executeIterations("BeanShell", iterations,
                        () -> (Boolean) interpreter.eval("uc1(42)"),
                        () -> {
                            interpreter.set("d", sampleData());
                            return ((Number) interpreter.eval("uc2(d)")).doubleValue();
                        },
                        () -> {
                            interpreter.set("ds", ds);
                            interpreter.set("payload", zipPayload);
                            return ((Number) interpreter.eval("uc3(ds, payload)")).intValue();
                        });
            } catch (Exception ex) {
                return ResultRow.skipped("BeanShell", ex);
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

    static class EspressoCompiled {
        final java.lang.reflect.Method uc1;
        final java.lang.reflect.Method uc2;
        final java.lang.reflect.Method uc3;

        EspressoCompiled(java.lang.reflect.Method uc1, java.lang.reflect.Method uc2, java.lang.reflect.Method uc3) {
            this.uc1 = uc1;
            this.uc2 = uc2;
            this.uc3 = uc3;
        }
    }

    static class MapContext extends org.apache.commons.jexl3.MapContext {
        private MapContext(Map<String, Object> data) {
            super(data);
        }

        static MapContext of(int age, Map<String, Object> data, JdbcDataSource ds, byte[] payload) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("age", age);
            vars.put("data", data);
            vars.put("ds", ds);
            vars.put("payload", payload);
            vars.put("Math", Math.class);
            return new MapContext(vars);
        }
    }
}
