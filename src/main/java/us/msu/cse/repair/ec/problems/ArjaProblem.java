package us.msu.cse.repair.ec.problems;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import javax.tools.JavaFileObject;
import jmetal.core.Solution;
import jmetal.encodings.variable.ArrayInt;
import jmetal.encodings.variable.Binary;
import jmetal.util.Configuration;
import jmetal.util.JMException;
import org.apache.commons.io.FileUtils;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import us.msu.cse.repair.core.AbstractRepairProblem;
import us.msu.cse.repair.core.filterrules.MIFilterRule;
import us.msu.cse.repair.core.parser.LCNode;
import us.msu.cse.repair.core.parser.ModificationPoint;
import us.msu.cse.repair.core.testexecutors.ITestExecutor;
import us.msu.cse.repair.core.util.IO;
import us.msu.cse.repair.ec.representation.ArrayIntAndBinarySolutionType;
import utdallas.edu.profl.replicate.enums.PatchCategory;

public class ArjaProblem extends AbstractRepairProblem {

    private static final long serialVersionUID = 1L;
    Double weight;

    Integer numberOfObjectives;
    Integer maxNumberOfEdits;
    Double mu;

    String initializationStrategy;

    Boolean miFilterRule;

    List<String> solutionMessages = new LinkedList<>();

    public ArjaProblem(Map<String, Object> parameters) throws Exception {
        super(parameters);

        weight = (Double) parameters.get("weight");
        if (weight == null) {
            weight = 0.5;
        }

        mu = (Double) parameters.get("mu");
        if (mu == null) {
            mu = 0.06;
        }

        numberOfObjectives = (Integer) parameters.get("numberOfObjectives");
        if (numberOfObjectives == null) {
            numberOfObjectives = 2;
        }

        initializationStrategy = (String) parameters.get("initializationStrategy");
        if (initializationStrategy == null) {
            initializationStrategy = "Prior";
        }

        miFilterRule = (Boolean) parameters.get("miFilterRule");
        if (miFilterRule == null) {
            miFilterRule = true;
        }

        maxNumberOfEdits = (Integer) parameters.get("maxNumberOfEdits");

        setProblemParams();
    }

    void setProblemParams() throws JMException {
        numberOfVariables_ = 2;
        numberOfObjectives_ = numberOfObjectives;
        numberOfConstraints_ = 0;
        problemName_ = "ArjaProblem";

        int size = modificationPoints.size();

        double[] prob = new double[size];
        if (initializationStrategy.equalsIgnoreCase("Prior")) {
            for (int i = 0; i < size; i++) {
                prob[i] = modificationPoints.get(i).getSuspValue() * mu;
            }
        } else if (initializationStrategy.equalsIgnoreCase("Random")) {
            for (int i = 0; i < size; i++) {
                prob[i] = 0.5;
            }
        } else {
            Configuration.logger_.severe("Initialization strategy " + initializationStrategy + " not found");
            throw new JMException("Exception in initialization strategy: " + initializationStrategy);
        }

        solutionType_ = new ArrayIntAndBinarySolutionType(this, size, prob);

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

        int size = modificationPoints.size();
        int[] array = ((ArrayInt) solution.getDecisionVariables()[0]).array_;
        BitSet bits = ((Binary) solution.getDecisionVariables()[1]).bits_;

        Map<String, ASTRewrite> astRewriters = new HashMap<String, ASTRewrite>();

        Map<Integer, Double> selectedMP = new HashMap<Integer, Double>();
        Map<Integer, LCNode> solutionInfo = new HashMap<>();

        for (int i = 0; i < size; i++) {
            if (bits.get(i)) {
                double suspValue = modificationPoints.get(i).getSuspValue();
                if (miFilterRule) {
                    String manipName = availableManipulations.get(i).get(array[i]);
                    ModificationPoint mp = modificationPoints.get(i);

                    Statement seed = null;
                    if (!mp.getIngredients().isEmpty()) {
                        seed = mp.getIngredients().get(array[i + size]);
                    }

                    int index = MIFilterRule.canFiltered(manipName, seed, modificationPoints.get(i));
                    if (index == -1) {
                        solutionInfo.put(i, modificationPoints.get(i).getLCNode());
                        selectedMP.put(i, suspValue);
                    } else if (index < mp.getIngredients().size()) {
                        array[i + size] = index;
                        solutionInfo.put(i, modificationPoints.get(i).getLCNode());
                        selectedMP.put(i, suspValue);
                    } else {
                        bits.set(i, false);
                    }
                } else {
                    solutionInfo.put(i, modificationPoints.get(i).getLCNode());
                    selectedMP.put(i, suspValue);
                }
            }
        }

        if (selectedMP.isEmpty()) {
            assignMaxObjectiveValues(solution);
            return;
        }

        List<LCNode> modifiedLines = new LinkedList();

        for (Integer index : selectedMP.keySet()) {
            modifiedLines.add(modificationPoints.get(index).getLCNode());
        }

        int numberOfEdits = selectedMP.size();
        List<Map.Entry<Integer, Double>> list = new ArrayList<Map.Entry<Integer, Double>>(selectedMP.entrySet());

        if (maxNumberOfEdits != null && selectedMP.size() > maxNumberOfEdits) {
            Collections.sort(list, new Comparator<Map.Entry<Integer, Double>>() {
                @Override
                public int compare(Entry<Integer, Double> o1, Entry<Integer, Double> o2) {
                    return o2.getValue().compareTo(o1.getValue());
                }
            });

            numberOfEdits = maxNumberOfEdits;
        }

        for (int i = 0; i < numberOfEdits; i++) {
            manipulateOneModificationPoint(list.get(i).getKey(), size, array, astRewriters);
        }

        for (int i = numberOfEdits; i < selectedMP.size(); i++) {
            bits.set(list.get(i).getKey(), false);
        }

        Map<String, String> modifiedJavaSources = getModifiedJavaSources(astRewriters);
        Map<String, JavaFileObject> compiledClasses = getCompiledClassesForTestExecution(modifiedJavaSources);

        boolean status = false;
        if (compiledClasses != null) {
            if (numberOfObjectives == 2 || numberOfObjectives == 3) {
                solution.setObjective(0, numberOfEdits);
            }
            try {
                status = invokeTestExecutor(compiledClasses, solution, modifiedLines);
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } else {
            assignMaxObjectiveValues(solution);
            System.out.println("Compilation fails!");
        }

        if (status) {
            solutionMessages.add("Repair patch found");
            save(solution, modifiedJavaSources, compiledClasses, list, numberOfEdits);
        } else {
            solutionMessages.add("Repair patch absent");
        }

        try {
            File file = new File(this.patchOutputRoot + "/PatchTestInfo/", "Patch_" + globalID + ".tests");
            Collections.sort(solutionMessages);
            FileUtils.writeLines(file, solutionMessages, "\n", true);
        } catch (IOException e) {
            System.out.println("Error occured when writing logFile: " + e.getMessage());
        }

        globalID++;
        evaluations++;
        System.out.println("One fitness evaluation is finished...");
    }

    void save(Solution solution, Map<String, String> modifiedJavaSources, Map<String, JavaFileObject> compiledClasses,
            List<Map.Entry<Integer, Double>> list, int numberOfEdits) {
        List<Integer> opList = new ArrayList<>();
        List<Integer> locList = new ArrayList<>();
        List<Integer> ingredList = new ArrayList<>();

        int[] var0 = ((ArrayInt) solution.getDecisionVariables()[0]).array_;
        int size = var0.length / 2;

        for (int i = 0; i < numberOfEdits; i++) {
            int loc = list.get(i).getKey();
            int op = var0[loc];
            int ingred = var0[loc + size];
            opList.add(op);
            locList.add(loc);
            ingredList.add(ingred);
        }

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

    boolean manipulateOneModificationPoint(int i, int size, int array[], Map<String, ASTRewrite> astRewriters)
            throws JMException {
        ModificationPoint mp = modificationPoints.get(i);
        String manipName = availableManipulations.get(i).get(array[i]);
        Statement ingredStatement = null;
        if (!mp.getIngredients().isEmpty()) {
            ingredStatement = mp.getIngredients().get(array[i + size]);
        }

        return manipulateOneModificationPoint(mp, manipName, ingredStatement, astRewriters);
    }

    boolean invokeTestExecutor(Map<String, JavaFileObject> compiledClasses, Solution solution, List<LCNode> modifiedLines) throws Exception {
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

            double ratioOfFailuresInPositive = testExecutor.getRatioOfFailuresInPositive();
            double ratioOfFailuresInNegative = testExecutor.getRatioOfFailuresInNegative();
            double fitness = weight * testExecutor.getRatioOfFailuresInPositive() + testExecutor.getRatioOfFailuresInNegative();

            System.out.println("Number of failed tests: " + (testExecutor.getFailureCountInNegative() + testExecutor.getFailureCountInPositive()));
            System.out.println("Weighted failure rate: " + fitness);

            if (numberOfObjectives == 1 || numberOfObjectives == 2) {
                solution.setObjective(numberOfObjectives - 1, fitness);
            } else {
                solution.setObjective(1, ratioOfFailuresInPositive);
                solution.setObjective(2, ratioOfFailuresInNegative);
            }

            Map<String, Double> modifiedMethods = new TreeMap<>();

            for (LCNode lcn : modifiedLines) {
                String fullMethodName = proflMethodCoverage.lookup(lcn.getClassName(), lcn.getLineNumber());
                String solutionMessage = String.format("Modified method %s at lineNumber=%d",
                        fullMethodName,
                        lcn.getLineNumber());
                System.out.println(solutionMessage);
                solutionMessages.add(solutionMessage);
                modifiedMethods.put(fullMethodName, profl.getGeneralMethodSusValues().get(fullMethodName));
            }

            String solutionMessage;
            if (failedTests.isEmpty()) {
                solutionMessage = "PatchCategory = CleanFix";
                profl.addCategoryEntry(PatchCategory.CleanAllFix, modifiedMethods);
            } else if (!failPassTests.isEmpty() || !passFailTests.isEmpty()) {
                solutionMessage = "PatchCategory = NoisyFix";
                profl.addCategoryEntry(PatchCategory.NoisyAllFix, modifiedMethods);
            } else if (passFailTests.isEmpty() && failPassTests.isEmpty()) {
                solutionMessage = "PatchCategory = NoneFix";
                profl.addCategoryEntry(PatchCategory.NoneFix, modifiedMethods);
            } else {
                solutionMessage = "PatchCategory = NegFix";
                profl.addCategoryEntry(PatchCategory.NegFix, modifiedMethods);
            }

            System.out.println(solutionMessage);
            solutionMessages.add(solutionMessage);

        } else {
            assignMaxObjectiveValues(solution);
            System.out.println("Timeout occurs!");
        }

        return status;
    }

    void assignMaxObjectiveValues(Solution solution) {
        for (int i = 0; i < solution.getNumberOfObjectives(); i++) {
            solution.setObjective(i, Double.MAX_VALUE);
        }
    }

}
