package us.msu.cse.repair.ec.problems;

import java.io.IOException;
import java.util.Collection;
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
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import us.msu.cse.repair.core.AbstractRepairProblem;
import us.msu.cse.repair.core.parser.LCNode;
import us.msu.cse.repair.core.parser.ModificationPoint;
import us.msu.cse.repair.core.testexecutors.ITestExecutor;
import us.msu.cse.repair.core.util.IO;
import us.msu.cse.repair.ec.representation.GenProgSolutionType;
import us.msu.cse.repair.ec.variable.Edits;
import utdallas.edu.profl.replicate.patchcategory.DefaultPatchCategories;
import utdallas.edu.profl.replicate.patchcategory.PatchCategory;

public class GenProgProblem extends AbstractRepairProblem {

    /**
     *
     */
    private static final long serialVersionUID = 1L;

    Double wpos;
    Double wneg;

    public GenProgProblem(Map<String, Object> parameters) throws Exception {
        super(parameters);
        wpos = (Double) parameters.get("wpos");
        if (wpos == null) {
            wpos = 1.0;
        }

        wneg = (Double) parameters.get("wneg");
        if (wneg == null) {
            wneg = 2.0;
        }

        setProblemParams();
    }

    void setProblemParams() throws JMException {
        numberOfVariables_ = 1;
        numberOfObjectives_ = 1;
        numberOfConstraints_ = 0;
        problemName_ = "GenProgProblem";

        int size = modificationPoints.size();

        double[] prob = new double[size];
        for (int i = 0; i < size; i++) {
            prob[i] = modificationPoints.get(i).getSuspValue();
        }

        solutionType_ = new GenProgSolutionType(this, size, prob);

        upperLimit_ = new double[2 * size];
        lowerLimit_ = new double[2 * size];
        for (int i = 0; i < size; i++) {
            lowerLimit_[i] = 0;
            upperLimit_[i] = availableManipulations.get(i).size() - 1;
        }

        for (int i = size; i < 2 * size; i++) {
            lowerLimit_[i] = 0;
            upperLimit_[i] = modificationPoints.get(i - size).getIngredients().size() - 1;
        }
    }

    @Override
    public void evaluate(Solution solution) throws JMException {
        System.out.println("-------------------------------------");
        System.out.println("One fitness evaluation starts...");
        Edits edits = (Edits) solution.getDecisionVariables()[0];
        List<Integer> locList = edits.getLocList();
        List<Integer> opList = edits.getOpList();
        List<Integer> ingredList = edits.getIngredList();

        Map<String, ASTRewrite> astRewriters = new HashMap<String, ASTRewrite>();
        Set<Integer> selectedModificationPointID = new HashSet();
        for (int i = 0; i < locList.size(); i++) {
            int loc = locList.get(i);
            int op = opList.get(i);
            int ingred = ingredList.get(i);
            ModificationPoint mp = modificationPoints.get(loc);
            selectedModificationPointID.add(loc);
            String manipName = availableManipulations.get(loc).get(op);
            Statement ingredStatement = mp.getIngredients().get(ingred);
            manipulateOneModificationPoint(mp, manipName, ingredStatement, astRewriters);
        }

        List<LCNode> modifiedLines = new LinkedList();

        for (Integer index : selectedModificationPointID) {
            modifiedLines.add(modificationPoints.get(index).getLCNode());
        }

        Map<String, String> modifiedJavaSources = getModifiedJavaSources(astRewriters);
        Map<String, JavaFileObject> compiledClasses = getCompiledClassesForTestExecution(modifiedJavaSources);

        boolean status = false;
        if (compiledClasses != null) {
            try {
                status = invokeTestExecutor(compiledClasses, solution, modifiedLines);
                IO.savePatch(modifiedJavaSources, srcJavaDir, this.patchOutputRoot, this.patchAttempts);
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } else {
            solution.setObjective(0, Double.MAX_VALUE);
            System.out.println("Compilation fails!");
        }

        if (status) {
            save(solution, modifiedJavaSources, compiledClasses);
        } else {
        }

        globalID++;
        evaluations++;
        System.out.println("One fitness evaluation is finished...");
    }

    void save(Solution solution, Map<String, String> modifiedJavaSources, Map<String, JavaFileObject> compiledClasses) {
        Edits edits = ((Edits) solution.getDecisionVariables()[0]);
        List<Integer> locList = edits.getLocList();
        List<Integer> opList = edits.getOpList();
        List<Integer> ingredList = edits.getIngredList();
        try {
            if (addTestAdequatePatch(opList, locList, ingredList)) {
                if (diffFormat) {
                    try {
                        IO.savePatch(modifiedJavaSources, srcJavaDir, this.patchOutputRoot, this.patchAttempts);
                    } catch (InterruptedException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
                saveTestAdequatePatch(opList, locList, ingredList);

            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    boolean invokeTestExecutor(Map<String, JavaFileObject> compiledClasses, Solution solution, List<LCNode> modifiedLines) throws Exception {
        this.patchAttempts += 1;

        Set<String> samplePosTests = getSamplePositiveTests();
        ITestExecutor testExecutor = getTestExecutor(compiledClasses, samplePosTests);

        boolean status = testExecutor.runTests();

        if (status && percentage != null && percentage < 1) {
            testExecutor = getTestExecutor(compiledClasses, getPositiveTests());
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
                    // System.out.println(String.format("[PASS->FAIL] test case found: %s", s));

                    passPassTests.remove(s);
                    passFailTests.add(s);
                } else if (negativeTests.contains(s)) {
                    // System.out.println(String.format("[FAIL->FAIL] test case found: %s", s));

                    failPassTests.remove(s);
                    failFailTests.add(s);
                } else {
                    System.out.println(String.format("Unknown test found: %s", s));
                }
            }

            for (String s : passPassTests) {
                // System.out.println(String.format("[PASS->PASS] test case found: %s", s));
            }

            for (String s : failPassTests) {
                // FSystem.out.println(String.format("[FAIL->PASS] test case found: %s", s));
            }

            Map<String, Double> modifiedMethods = new TreeMap<>();
            Map<String, Collection<Integer>> modifiedMethodsLines = new TreeMap<>();

            System.out.println(String.format(" --- Information for Patch %d ---", this.patchAttempts));

            for (LCNode lcn : modifiedLines) {

                String fullMethodName = profl.getMethodCoverage().lookup(lcn.getClassName(), lcn.getLineNumber());
                String solutionMessage = String.format("Modified method %s at lineNumber=%d", fullMethodName, lcn.getLineNumber());

                Collection<Integer> values;

                if (modifiedMethodsLines.get(fullMethodName) == null) {
                    values = new LinkedList();
                } else {
                    values = modifiedMethodsLines.get(fullMethodName);
                }

                values.add(lcn.getLineNumber());

                modifiedMethodsLines.put(fullMethodName, values);
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

            System.out.println("Patch Category = " + pc.getCategoryName());
            profl.addCategoryEntry(pc, modifiedMethods);

            this.saveTests(failFailTests, failPassTests, passFailTests, passPassTests, pc, modifiedMethodsLines);
            this.saveProflInformation();

            this.patchAttempts += 1;

        } else {
            solution.setObjective(0, Double.MAX_VALUE);
            System.out.println("Timeout occurs!");
        }

        return status;
    }

 

}
