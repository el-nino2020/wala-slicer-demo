import com.ibm.wala.classLoader.Language;
import com.ibm.wala.classLoader.ShrikeBTMethod;
import com.ibm.wala.core.tests.callGraph.CallGraphTestUtil;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.core.util.io.FileProvider;
import com.ibm.wala.core.util.strings.Atom;
import com.ibm.wala.core.viz.PDFViewUtil;
import com.ibm.wala.examples.drivers.PDFSDG;
import com.ibm.wala.examples.drivers.PDFTypeHierarchy;
import com.ibm.wala.examples.properties.WalaExamplesProperties;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.slicer.*;
import com.ibm.wala.ipa.slicer.Slicer.ControlDependenceOptions;
import com.ibm.wala.ipa.slicer.Slicer.DataDependenceOptions;
import com.ibm.wala.ipa.slicer.Statement.Kind;
import com.ibm.wala.properties.WalaProperties;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.GraphIntegrity;
import com.ibm.wala.util.graph.GraphIntegrity.UnsoundGraphException;
import com.ibm.wala.util.graph.GraphSlicer;
import com.ibm.wala.util.io.CommandLine;
import com.ibm.wala.util.viz.DotUtil;
import com.ibm.wala.util.viz.NodeDecorator;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Properties;

/**
 * This simple example WALA application computes a slice (see {@link Slicer}) and fires off the PDF
 * viewer to view a dot-ted representation of the slice.
 *
 * <p>This is an example program on how to use the slicer.
 *
 * <p>See the 'PDFSlice' launcher included in the 'launchers' directory.
 *
 * @author sfink
 * @see Slicer
 */
public class PDFSlice {

    /**
     * Name of the postscript file generated by dot
     */
    private static final String PDF_FILE = "slice.pdf";

    /**
     * Usage: PDFSlice -appJar [jar file name] -mainClass [main class] -srcCaller [method name]
     * -srcCallee [method name] -dd [data dependence options] -cd [control dependence options] -dir
     * [forward|backward] -outputFile [file path] -printSlice [true|false]
     *
     * <ul>
     *   <li>"jar file name" should be something like "c:/temp/testdata/java_cup.jar"
     *   <li>"main class", L 加上全类名 e.g. 'Ljava/lang/String' 'Lcom/ibm/wala/FakeRootClass'
     *   <li>"method name" should be the name of a method. This takes a slice from the statement that
     *       calls "srcCallee" from "srcCaller"
     *   <li>"data dependence options" can be one of "full", "no_base_ptrs", "no_base_no_heap",
     *       "no_heap", "no_base_no_heap_no_cast", or "none".
     *   <li>"control dependence options" can be "full" or "none"
     *   <li>the -dir argument tells whether to compute a forwards or backwards slice.
     *   <li> outputFile: 存放 slice 的文件
     *   <li> printSlice： 是否将 slice 打印至命令行
     * </ul>
     *
     * @see com.ibm.wala.ipa.slicer.Slicer.DataDependenceOptions
     */
    public static void main(String[] args) throws IllegalArgumentException, CancelException, IOException {
        run(args);
    }

    /**
     * see {@link #main(String[])} for command-line arguments
     */
    public static Process run(String[] args) throws IllegalArgumentException, CancelException, IOException {
        // parse the command-line into a Properties object
        Properties p = CommandLine.parse(args);
//        System.out.println(p);
        // validate that the command-line has the expected format
        validateCommandLine(p);

        if (p.getProperty("printSlice") == null) {
            System.err.println("-printSlice is null, use true as default");
            p.setProperty("printSlice", "true");
        }
        if (p.getProperty("jUnitEntry") == null) {
            System.err.println("-printSlice is null, use false as default");
            p.setProperty("jUnitEntry", "false");
        }

        // run the applications
        return run(p.getProperty("appJar"), p.getProperty("mainClass"), p.getProperty("srcCaller"), p.getProperty("srcCallee"), goBackward(p), PDFSDG.getDataDependenceOptions(p), PDFSDG.getControlDependenceOptions(p),
                p.getProperty("outputFile"),
                Boolean.parseBoolean(p.getProperty("printSlice")),
                Boolean.parseBoolean(p.getProperty("jUnitEntry")),
                p);
    }

    /**
     * Should the slice be a backwards slice?
     */
    private static boolean goBackward(Properties p) {
        return !p.getProperty("dir", "backward").equals("forward");
    }


    public static CGNode findMethod(CallGraph cg, String name, boolean printAllMethod) {
        Atom a = Atom.findOrCreateUnicodeAtom(name);
        for (CGNode n : cg) {
            if (printAllMethod) {
                System.out.println(n.getMethod().getDeclaringClass().toString() + ":" + n.getMethod().getName());
            }
            if (n.getMethod().getName().equals(a)) {
                return n;
            }
        }
        System.err.println("call graph " + cg);
        Assertions.UNREACHABLE("failed to find method " + name);
        return null;
    }

    /**
     * Compute a slice from a call statements, dot it, and fire off the PDF viewer to visualize the
     * result
     *
     * @param appJar     should be something like "c:/temp/testdata/java_cup.jar"
     * @param mainClass  should be something like "c:/temp/testdata/java_cup.jar"
     * @param srcCaller  name of the method containing the statement of interest
     * @param srcCallee  name of the method called by the statement of interest
     * @param goBackward do a backward slice?
     * @param dOptions   options controlling data dependence
     * @param cOptions   options controlling control dependence
     * @return a Process running the PDF viewer to visualize the dot'ted representation of the slice
     */
    public static Process run(String appJar, String mainClass, String srcCaller, String srcCallee, boolean goBackward, DataDependenceOptions dOptions, ControlDependenceOptions cOptions, String outputFile, boolean printSlice, boolean jUnitEntry, Properties p) throws IllegalArgumentException, CancelException, IOException {
        if (outputFile == null) {
            System.err.println("-outputFile is null, the slice will not be saved into a file.");
        }

        // java 的类名是 abc.def.Main，但是 wala 的规范是 labc/def/Main
        // 先转化一下
        if (!mainClass.startsWith("L")) {
            mainClass = 'L' + mainClass;
        }
        mainClass = mainClass.replace('.', '/');
        if (printSlice) {
            System.out.println("mainClass: " + mainClass);
        }

        try {
            long time = System.currentTimeMillis();
            // create an analysis scope representing the appJar as a J2SE application
            File exclusionFile = null;
            if (Boolean.parseBoolean(p.getProperty("useExclusionFile", "true"))) {
                if (Boolean.parseBoolean(p.getProperty("useDefaultExclusionFile", "true"))) {
                    exclusionFile = new FileProvider().getFile(CallGraphTestUtil.REGRESSION_EXCLUSIONS);
                    System.out.println("using default exclusion file");
                } else {
                    exclusionFile = new FileProvider().getFile(p.getProperty("CustomExclusionFilePath"));
                    System.out.println("using custom exclusion file");
                }
            }

            AnalysisScope scope = AnalysisScopeReader.instance.makeJavaBinaryAnalysisScope(appJar, exclusionFile);
//            AnalysisScope scope = AnalysisScopeReader.instance.makeJavaBinaryAnalysisScope(appJar,
//                    new FileProvider().getFile("src/main/resources/CustomRegressionExclusions.txt"));
            // build a class hierarchy, call graph, and system dependence graph
            ClassHierarchy cha = ClassHierarchyFactory.make(scope);
            Iterable<Entrypoint> entryPoints;
            if (jUnitEntry) {

                String junitVersion = p.getProperty("junitVersion");
                switch (junitVersion) {
                    case "4":
                        entryPoints = MyJUnitEntryPoints.make(cha, mainClass, printSlice);
                        break;
                    case "3":
                        entryPoints = MyJUnitEntryPoints.makeOne(cha, p.getProperty("targetPackageName"), p.getProperty("targetSimpleClassName"), p.getProperty("targetMethodName"));
                        break;
                    default:
                        throw new RuntimeException();
                }
            } else {
                entryPoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, mainClass);
            }
            AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entryPoints);
            options.setReflectionOptions(AnalysisOptions.ReflectionOptions.NONE);

//            CallGraphBuilder<InstanceKey> builder = Util.makeVanillaZeroOneCFABuilder(Language.JAVA, options, new AnalysisCacheImpl(), cha);
            CallGraphBuilder<InstanceKey> builder = Util.makeZeroOneCFABuilder(Language.JAVA, options, new AnalysisCacheImpl(), cha);
//            CallGraphBuilder<InstanceKey> builder = Util.makeZeroCFABuilder(Language.JAVA, options, new AnalysisCacheImpl(), cha);

            CallGraph cg = builder.makeCallGraph(options, null);

            // find the call statement of interest
//            CGNode callerNode = CallGraphSearchUtil.findMethod(cg, srcCaller);
            CGNode callerNode = findMethod(cg, srcCaller, printSlice);
            Statement s = SlicerUtil.findCallTo(callerNode, srcCallee);
            System.out.println("Statement: " + s);
            System.out.println("Line of this statement: " + mapToSourceCodeLine(s));
            System.out.format("build graph time: %.3f s\n", (System.currentTimeMillis() - time) / 1000.0);
            time = System.currentTimeMillis();

            // compute the slice as a collection of statements
            final Collection<Statement> slice;
            if (goBackward) {
                final PointerAnalysis<InstanceKey> pointerAnalysis = builder.getPointerAnalysis();
                slice = Slicer.computeBackwardSlice(s, cg, pointerAnalysis, dOptions, cOptions);
                System.out.format("slice time: %.3f s\n", (System.currentTimeMillis() - time) / 1000.0);
                time = System.currentTimeMillis();
            } else {
                // for forward slices ... we actually slice from the return value of
                // calls.
                s = getReturnStatementForCall(s);
                final PointerAnalysis<InstanceKey> pointerAnalysis = builder.getPointerAnalysis();
                slice = Slicer.computeForwardSlice(s, cg, pointerAnalysis, dOptions, cOptions);
            }
//            SlicerUtil.dumpSlice(slice);
            printSourceCodeLines(slice, mainClass, outputFile, printSlice);
            if (true) return null;

            SDG<InstanceKey> sdg = new SDG<>(cg, builder.getPointerAnalysis(), dOptions, cOptions);

            // create a view of the SDG restricted to nodes in the slice
            Graph<Statement> g = pruneSDG(sdg, slice);

            sanityCheck(slice, g);


            // load Properties from standard WALA and the WALA examples project
            p = null;
            try {
                p = WalaExamplesProperties.loadProperties();
                p.putAll(WalaProperties.loadProperties());
            } catch (WalaException e) {
                e.printStackTrace();
                Assertions.UNREACHABLE();
            }
            // create a dot representation.
            String psFile = p.getProperty(WalaProperties.OUTPUT_DIR) + File.separatorChar + PDF_FILE;
            String dotExe = p.getProperty(WalaExamplesProperties.DOT_EXE);
            DotUtil.dotify(g, makeNodeDecorator(), PDFTypeHierarchy.DOT_FILE, psFile, dotExe);

            // fire off the PDF viewer
            String gvExe = p.getProperty(WalaExamplesProperties.PDFVIEW_EXE);
            return PDFViewUtil.launchPDFView(psFile, gvExe);

        } catch (WalaException e) {
            // something bad happened.
            e.printStackTrace();
            return null;
        }
    }

    private static int mapToSourceCodeLine(Statement s) {
        if (s.getKind() != Statement.Kind.NORMAL) {
            return -1;
        }
        try {
            int instructionIndex = ((NormalStatement) s).getInstructionIndex();
            return s.getNode().getMethod().getLineNumber(((ShrikeBTMethod) s.getNode().getMethod()).getBytecodeIndex(instructionIndex));
        } catch (Exception e) {
            return -1;
        }
    }

    private static void printSourceCodeLines(Collection<Statement> slice, String mainClass, String outputFile, boolean printSlice) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (Statement s : slice) {
            if (s.getKind() == Statement.Kind.NORMAL) { // ignore special kinds of statements
                // 只要源码
//                if (!Objects.equals(s.getNode().getMethod().getDeclaringClass().getReference().getName().toString(), mainClass)) {
//                    continue;
//                }
                if (!Objects.equals(s.getNode().getMethod().getDeclaringClass().getClassLoader().toString(), "Application")) {
                    continue;
                }
                int srcLineNumber = mapToSourceCodeLine(s);
                set.add(String.format("%s:%d", s.getNode().getMethod(), srcLineNumber));
            }
        }
        System.out.println("LOC: " + set.size());
        if (printSlice) {
            set.forEach(System.out::println);
        }
        if (outputFile == null) {
            return;
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile, StandardCharsets.UTF_8, true))) {
            writer.write("=======================================\n");
            writer.write(LocalDateTime.now().toString());
            writer.newLine();
            writer.newLine();

            for (String s : set) {
                writer.write(s);
                writer.newLine();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }


    }

    /**
     * check that g is a well-formed graph, and that it contains exactly the number of nodes in the
     * slice
     */
    private static void sanityCheck(Collection<Statement> slice, Graph<Statement> g) {
        try {
            GraphIntegrity.check(g);
        } catch (UnsoundGraphException e1) {
            e1.printStackTrace();
            Assertions.UNREACHABLE();
        }
        Assertions.productionAssertion(g.getNumberOfNodes() == slice.size(), "panic " + g.getNumberOfNodes() + " " + slice.size());
    }

    /**
     * If s is a call statement, return the statement representing the normal return from s
     */
    public static Statement getReturnStatementForCall(Statement s) {
        if (s.getKind() == Kind.NORMAL) {
            NormalStatement n = (NormalStatement) s;
            SSAInstruction st = n.getInstruction();
            if (st instanceof SSAInvokeInstruction) {
                SSAAbstractInvokeInstruction call = (SSAAbstractInvokeInstruction) st;
                if (call.getCallSite().getDeclaredTarget().getReturnType().equals(TypeReference.Void)) {
                    throw new IllegalArgumentException("this driver computes forward slices from the return value of calls.\n" + "Method " + call.getCallSite().getDeclaredTarget().getSignature() + " returns void.");
                }
                return new NormalReturnCaller(s.getNode(), n.getInstructionIndex());
            } else {
                return s;
            }
        } else {
            return s;
        }
    }

    /**
     * return a view of the sdg restricted to the statements in the slice
     */
    public static Graph<Statement> pruneSDG(SDG<InstanceKey> sdg, final Collection<Statement> slice) {
        return GraphSlicer.prune(sdg, slice::contains);
    }

    /**
     * @return a NodeDecorator that decorates statements in a slice for a dot-ted representation
     */
    public static NodeDecorator<Statement> makeNodeDecorator() {
        return s -> {
            switch (s.getKind()) {
                case HEAP_PARAM_CALLEE:
                case HEAP_PARAM_CALLER:
                case HEAP_RET_CALLEE:
                case HEAP_RET_CALLER:
                    HeapStatement h = (HeapStatement) s;
                    return s.getKind() + "\\n" + h.getNode() + "\\n" + h.getLocation();
                case NORMAL:
                    NormalStatement n = (NormalStatement) s;
                    return n.getInstruction() + "\\n" + n.getNode().getMethod().getSignature();
                case PARAM_CALLEE:
                    ParamCallee paramCallee = (ParamCallee) s;
                    return s.getKind() + " " + paramCallee.getValueNumber() + "\\n" + s.getNode().getMethod().getName();
                case PARAM_CALLER:
                    ParamCaller paramCaller = (ParamCaller) s;
                    return s.getKind() + " " + paramCaller.getValueNumber() + "\\n" + s.getNode().getMethod().getName() + "\\n" + paramCaller.getInstruction().getCallSite().getDeclaredTarget().getName();
                case EXC_RET_CALLEE:
                case EXC_RET_CALLER:
                case NORMAL_RET_CALLEE:
                case NORMAL_RET_CALLER:
                case PHI:
                default:
                    return s.toString();
            }
        };
    }

    /**
     * Validate that the command-line arguments obey the expected usage.
     *
     * <p>Usage:
     *
     * <ul>
     *   <li>args[0] : "-appJar"
     *   <li>args[1] : something like "c:/temp/testdata/java_cup.jar"
     *   <li>args[2] : "-mainClass"
     *   <li>args[3] : something like "Lslice/TestRecursion" *
     *   <li>args[4] : "-srcCallee"
     *   <li>args[5] : something like "print" *
     *   <li>args[4] : "-srcCaller"
     *   <li>args[5] : something like "main"
     * </ul>
     *
     * @throws UnsupportedOperationException if command-line is malformed.
     */
    static void validateCommandLine(Properties p) {
        if (p.get("appJar") == null) {
            throw new UnsupportedOperationException("expected command-line to include -appJar");
        }
        if (p.get("mainClass") == null) {
            throw new UnsupportedOperationException("expected command-line to include -mainClass");
        }
        if (p.get("srcCallee") == null) {
            throw new UnsupportedOperationException("expected command-line to include -srcCallee");
        }
        if (p.get("srcCaller") == null) {
            throw new UnsupportedOperationException("expected command-line to include -srcCaller");
        }
    }
}