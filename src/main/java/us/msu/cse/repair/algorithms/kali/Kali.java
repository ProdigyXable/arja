package us.msu.cse.repair.algorithms.kali;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.tools.JavaFileObject;
import jmetal.core.Solution;
import jmetal.util.JMException;
import org.apache.commons.io.FileUtils;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import us.msu.cse.repair.core.AbstractRepairProblem;
import us.msu.cse.repair.core.manipulation.InsertReturnManipulation;
import us.msu.cse.repair.core.manipulation.RedirectBranchManipulation;
import us.msu.cse.repair.core.parser.LCNode;
import us.msu.cse.repair.core.parser.ModificationPoint;
import us.msu.cse.repair.core.testexecutors.ITestExecutor;
import us.msu.cse.repair.core.util.IO;
import utdallas.edu.profl.replicate.patchcategory.DefaultPatchCategories;

public class Kali extends AbstractRepairProblem {

    /**
     *
     */
    private static final long serialVersionUID = 1L;

    private long initTime = 0;

    public Kali(Map<String, Object> parameters) throws Exception {
        super(parameters);
        Collections.sort(modificationPoints, new Comparator<ModificationPoint>() {
            @Override
            public int compare(ModificationPoint o1, ModificationPoint o2) {
                Double d1 = o1.getSuspValue();
                Double d2 = o2.getSuspValue();
                return d2.compareTo(d1);
            }
        });
    }

    public boolean execute() throws Exception {
        initTime = System.currentTimeMillis();
        boolean status = false;

        status |= redirectBranch();
        status |= insertReturn();
        status |= deleteStatement();

        return status;
    }

    boolean redirectBranch() throws Exception {
        for (int i = 0; i < modificationPoints.size(); i++) {
            ModificationPoint mp = modificationPoints.get(i);
            for (int k = 0; k < 2; k++) {
                System.out.println("------------");
                globalID++;
                boolean status = redirectBranch(mp, k == 0);
                if (status) {
                    saveRedirectBranch(mp, k == 0);
                    return true;
                }
            }
        }
        return false;
    }

    boolean redirectBranch(ModificationPoint mp, boolean flag) throws Exception {
        List<LCNode> modifiedLines = new LinkedList<>();
        modifiedLines.add(mp.getLCNode());

        ASTRewrite rewriter = getASTRewriter(mp);
        RedirectBranchManipulation manipulation = new RedirectBranchManipulation(mp, null, rewriter);
        manipulation.setCondition(flag);

        if (!manipulation.manipulate()) {
            return false;
        }

        return runTests(mp, rewriter, modifiedLines);
    }

    boolean insertReturn() throws Exception {
        for (int i = 0; i < modificationPoints.size(); i++) {
            for (int k = 0; k < 2; k++) {
                System.out.println("------------");
                globalID++;
                ModificationPoint mp = modificationPoints.get(i);
                boolean status = insertReturn(mp, k == 0);
                if (status) {
                    saveInsertReturn(mp, k == 0);
                    return true;
                }
            }
        }
        return false;
    }

    boolean insertReturn(ModificationPoint mp, boolean flag) throws Exception {
        List<LCNode> modifiedLines = new LinkedList<>();
        modifiedLines.add(mp.getLCNode());

        ASTRewrite rewriter = getASTRewriter(mp);
        InsertReturnManipulation manipulation = new InsertReturnManipulation(mp, null, rewriter);
        manipulation.setReturnStatus(flag);

        if (!manipulation.manipulate()) {
            return false;
        }

        return runTests(mp, rewriter, modifiedLines);

    }

    boolean deleteStatement() throws Exception {
        for (int i = 0; i < modificationPoints.size(); i++) {
            System.out.println("------------");
            globalID++;
            ModificationPoint mp = modificationPoints.get(i);
            boolean status = deleteStatement(mp);
            if (status) {
                saveDeleteStatement(mp);
                return true;
            }
        }
        return false;
    }

    boolean deleteStatement(ModificationPoint mp) throws Exception {
        List<LCNode> modifiedLines = new LinkedList<>();
        modifiedLines.add(mp.getLCNode());

        Map<String, ASTRewrite> astRewriters = new HashMap<String, ASTRewrite>();
        if (!manipulateOneModificationPoint(mp, "Delete", null, astRewriters)) {
            return false;
        }
        Map<String, String> modifiedJavaSources = getModifiedJavaSources(astRewriters);
        Map<String, JavaFileObject> compiledClasses = getCompiledClassesForTestExecution(modifiedJavaSources);
        if (compiledClasses != null) {
            boolean flag = invokeTestExecutor(compiledClasses, modifiedLines);
            if (flag && diffFormat) {
                IO.savePatch(modifiedJavaSources, srcJavaDir, patchOutputRoot, 0);
            }
            return flag;
        } else {
            return false;
        }
    }

    boolean invokeTestExecutor(Map<String, JavaFileObject> compiledClasses, List<LCNode> modifiedLines) throws Exception {
        Set<String> samplePosTests = getSamplePositiveTests();
        ITestExecutor testExecutor = getTestExecutor(compiledClasses, samplePosTests);

        boolean status = testExecutor.runTests();
        if (status && percentage != null && percentage < 1) {
            testExecutor = getTestExecutor(compiledClasses, positiveTests);
            status = testExecutor.runTests();
        }

        if (!testExecutor.isExceptional()) {
            Set<String> passPassTests = new HashSet(positiveTests);
            Set<String> failPassTests = new HashSet(negativeTests);

            Set<String> passFailTests = new HashSet();
            Set<String> failFailTests = new HashSet();

            Set<String> failedTests = testExecutor.getFailedTests();

            for (String s : failedTests) {
                if (positiveTests.contains(s)) {
                    String message = String.format("[PASS->FAIL] test case found: %s", s);
                    //    System.out.println(message);
                    solutionMessages.add(message);

                    passPassTests.remove(s);
                    passFailTests.add(s);
                } else if (negativeTests.contains(s)) {
                    String message = String.format("[FAIL->FAIL] test case found: %s", s);
                    //    System.out.println(message);
                    solutionMessages.add(message);

                    failPassTests.remove(s);
                    failFailTests.add(s);
                } else {
                    System.out.println(String.format("Unknown test found: %s", s));
                }
            }

            for (String s : passPassTests) {
                String message = String.format("[PASS->PASS] test case found: %s", s);
                // System.out.println(message);
                solutionMessages.add(message);
            }

            for (String s : failPassTests) {
                String message = String.format("[FAIL->PASS] test case found: %s", s);
                // System.out.println(message);
                solutionMessages.add(message);
            }

            Map<String, Double> modifiedMethods = new TreeMap<>();

            for (LCNode lcn : modifiedLines) {
                String fullMethodName = proflMethodCoverage.lookup(lcn.getClassName(), lcn.getLineNumber());
                String solutionMessage = String.format("Modified method %s at lineNumber=%d",
                        fullMethodName,
                        lcn.getLineNumber());
                solutionMessages.add(solutionMessage);
                modifiedMethods.put(fullMethodName, profl.getGeneralMethodSusValues().get(fullMethodName));
                System.out.println(solutionMessage);
            }

            String solutionMessage;
            if (failedTests.isEmpty()) {
                solutionMessage = "PatchCategory = CleanFix";
                profl.addCategoryEntry(DefaultPatchCategories.CLEAN_FIX, modifiedMethods);
            } else if (!failPassTests.isEmpty() || !passFailTests.isEmpty()) {
                solutionMessage = "PatchCategory = NoisyFix";
                profl.addCategoryEntry(DefaultPatchCategories.NOISY_FIX, modifiedMethods);
            } else if (passFailTests.isEmpty() && failPassTests.isEmpty()) {
                solutionMessage = "PatchCategory = NoneFix";
                profl.addCategoryEntry(DefaultPatchCategories.NONE_FIX, modifiedMethods);
            } else {
                solutionMessage = "PatchCategory = NegFix";
                profl.addCategoryEntry(DefaultPatchCategories.NEG_FIX, modifiedMethods);
            }

            System.out.println(solutionMessage);
            solutionMessages.add(solutionMessage);
        } else {
            String message = "Test suite exception detected";
            System.out.println(message);
            solutionMessages.add(message);
        }
        return status;
    }

    boolean runTests(ModificationPoint mp, ASTRewrite rewriter, List<LCNode> modifiedLines) throws Exception {
        Map<String, ASTRewrite> astRewriters = new HashMap<String, ASTRewrite>();
        astRewriters.put(mp.getSourceFilePath(), rewriter);

        Map<String, String> modifiedJavaSources = getModifiedJavaSources(astRewriters);
        Map<String, JavaFileObject> compiledClasses = getCompiledClassesForTestExecution(modifiedJavaSources);

        if (compiledClasses != null) {
            boolean flag = invokeTestExecutor(compiledClasses, modifiedLines);
            if (flag && diffFormat) {
                IO.savePatch(modifiedJavaSources, srcJavaDir, patchOutputRoot, globalID);
            }
            return flag;
        } else {
            return false;
        }
    }

    ASTRewrite getASTRewriter(ModificationPoint mp) {
        String sourceFilePath = mp.getSourceFilePath();
        CompilationUnit unit = sourceASTs.get(sourceFilePath);
        ASTRewrite rewriter = ASTRewrite.create(unit.getAST());
        return rewriter;
    }

    @Override
    public void evaluate(Solution solution) throws JMException {
        // TODO Auto-generated method stub

    }

    void saveRedirectBranch(ModificationPoint mp, boolean flag) throws IOException {
        Statement faulty = mp.getStatement();
        String data = "RedirectBranch " + flag + " " + mp.getSourceFilePath() + " " + mp.getLCNode().getLineNumber()
                + " " + mp.getSuspValue() + "\n";
        data += faulty.toString();
        data += "**************************************************\n";

        long estimatedTime = System.currentTimeMillis() - initTime;
        data += "EstimatedTime: " + estimatedTime + "\n";
        savePatch(data);
    }

    void saveInsertReturn(ModificationPoint mp, boolean flag) throws IOException {
        Statement faulty = mp.getStatement();
        String data = "InsertReturn " + flag + " " + mp.getSourceFilePath() + " " + mp.getLCNode().getLineNumber() + " "
                + mp.getSuspValue() + "\n";
        ;
        data += faulty.toString();
        data += "**************************************************\n";

        long estimatedTime = System.currentTimeMillis() - initTime;
        data += "EstimatedTime: " + estimatedTime + "\n";
        savePatch(data);
    }

    void saveDeleteStatement(ModificationPoint mp) throws IOException {
        Statement faulty = mp.getStatement();
        String data = "Delete " + mp.getSourceFilePath() + " " + mp.getLCNode().getLineNumber() + " "
                + mp.getSuspValue() + "\n";
        data += faulty.toString();
        data += "**************************************************\n";

        long estimatedTime = System.currentTimeMillis() - initTime;
        data += "EstimatedTime: " + estimatedTime + "\n";
        savePatch(data);
    }

    void savePatch(String data) throws IOException {
        try {
            File file = new File(this.patchOutputRoot + "/PatchTestInfo-Kali/", "Patch_" + globalID + ".tests");
            Collections.sort(solutionMessages);
            FileUtils.writeLines(file, solutionMessages, "\n", true);
        } catch (IOException e) {
            System.out.println("Error occured when writing logFile: " + e.getMessage());
        }

        File patchFile = new File(patchOutputRoot + "/RepairPatches-Kali/", "Patch_" + globalID + ".patch");
        if (patchFile.exists()) {
            patchFile.delete();
        }

        FileUtils.writeByteArrayToFile(patchFile, data.getBytes());
    }

}
