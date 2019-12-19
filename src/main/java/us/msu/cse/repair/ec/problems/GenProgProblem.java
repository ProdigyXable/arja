package us.msu.cse.repair.ec.problems;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
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
        solutionMessages.clear();
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
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } else {
            solution.setObjective(0, Double.MAX_VALUE);
            System.out.println("Compilation fails!");
        }

        if (status) {
            solutionMessages.add("Repair patch found");
            save(solution, modifiedJavaSources, compiledClasses);
        } else {
            solutionMessages.add("Repair patch absent");
        }

        try {
            File file = new File(this.patchOutputRoot + "/PatchTestInfo-GenProg/", "Patch_" + globalID + ".tests");
            Collections.sort(solutionMessages);
            FileUtils.writeLines(file, solutionMessages, "\n", true);
        } catch (IOException e) {
            System.out.println("Error occured when writing logFile: " + e.getMessage());
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
                        IO.savePatch(modifiedJavaSources, srcJavaDir, this.patchOutputRoot, globalID);
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
        Set<String> samplePosTests = getSamplePositiveTests();
        ITestExecutor testExecutor = getTestExecutor(compiledClasses, samplePosTests);

        boolean status = testExecutor.runTests();

        if (status && percentage != null && percentage < 1) {
            testExecutor = getTestExecutor(compiledClasses, getPositiveTests());
            status = testExecutor.runTests();
        }

        int failureCountInPositive = testExecutor.getFailureCountInPositive();
        int failureCountInNegative = testExecutor.getFailureCountInNegative();

        boolean allFailed = (failureCountInPositive == samplePosTests.size() && failureCountInNegative == negativeTests.size());
        
        if (!testExecutor.isExceptional() /* && !allFailed */) {
            double fitness = wpos * failureCountInPositive + wneg * failureCountInNegative;
            solution.setObjective(0, fitness);
            /* PROFL BLOCK START */
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
            /* PROFL BLOCK END */

            System.out.println("Number of failed tests: " + (failureCountInPositive + failureCountInNegative));
            System.out.println("Fitness: " + fitness);

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
            solution.setObjective(0, Double.MAX_VALUE);
            System.out.println("Timeout occurs!");
        }

        return status;
    }

}
