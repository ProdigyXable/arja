package us.msu.cse.repair.algorithms.kali;

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
import utdallas.edu.profl.replicate.patchcategory.PatchCategory;

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
                IO.savePatch(modifiedJavaSources, srcJavaDir, patchOutputRoot, this.patchAttempts);
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
                    System.out.println(String.format("[PASS->FAIL] test case found: %s", s));

                    passPassTests.remove(s);
                    passFailTests.add(s);
                } else if (negativeTests.contains(s)) {
                    System.out.println(String.format("[FAIL->FAIL] test case found: %s", s));

                    failPassTests.remove(s);
                    failFailTests.add(s);
                } else {
                    System.out.println(String.format("Unknown test found: %s", s));
                }
            }

            for (String s : passPassTests) {
                System.out.println(String.format("[PASS->PASS] test case found: %s", s));
            }

            for (String s : failPassTests) {
                System.out.println(String.format("[FAIL->PASS] test case found: %s", s));
            }

            Map<String, Double> modifiedMethods = new TreeMap<>();

            for (LCNode lcn : modifiedLines) {
                String fullMethodName = profl.getMethodCoverage().lookup(lcn.getClassName(), lcn.getLineNumber());
                String solutionMessage = String.format("Modified method %s at lineNumber=%d", fullMethodName, lcn.getLineNumber());
                modifiedMethods.put(fullMethodName, profl.getGeneralMethodSusValues().get(fullMethodName));
                System.out.println(solutionMessage);
            }

            PatchCategory pc;

            if (failPassTests.size() > 0 && passFailTests.size() == 0) {
                if (failFailTests.size() == 0) {
                    pc = DefaultPatchCategories.CLEAN_FIX_FULL;
                } else {
                    pc = DefaultPatchCategories.CLEAN_FIX_PARTIAL;
                }
            } else if (failPassTests.size() > 0 && passFailTests.size() > 0) {
                if (failFailTests.size() == 0) {
                    pc = DefaultPatchCategories.NOISY_FIX_FULL;
                } else {
                    pc = DefaultPatchCategories.NOISY_FIX_PARTIAL;
                }
            } else if (failPassTests.size() == 0 && passFailTests.size() == 0) {
                pc = DefaultPatchCategories.NONE_FIX;
            } else {
                pc = DefaultPatchCategories.NEG_FIX;
            }

            profl.addCategoryEntry(pc, modifiedMethods);
            
            this.patchAttempts += 1;

        } else {
            System.out.println("Test suite exception detected");
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
                IO.savePatch(modifiedJavaSources, srcJavaDir, patchOutputRoot, this.patchAttempts);
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
    }

    void saveDeleteStatement(ModificationPoint mp) throws IOException {
        Statement faulty = mp.getStatement();
        String data = "Delete " + mp.getSourceFilePath() + " " + mp.getLCNode().getLineNumber() + " "
                + mp.getSuspValue() + "\n";
        data += faulty.toString();
        data += "**************************************************\n";

        long estimatedTime = System.currentTimeMillis() - initTime;
        data += "EstimatedTime: " + estimatedTime + "\n";
    }
}
