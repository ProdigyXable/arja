package us.msu.cse.repair.algorithms.arja;

import jmetal.metaheuristics.nsgaII.NSGAII;
import us.msu.cse.repair.core.AbstractRepairAlgorithm;
import us.msu.cse.repair.ec.problems.ArjaProblem;

public class Arja extends AbstractRepairAlgorithm {

    public Arja(ArjaProblem problem) throws Exception {
        algorithm = new NSGAII(problem);
    }
}
