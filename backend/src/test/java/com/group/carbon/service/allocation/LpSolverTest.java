package com.group.carbon.service.allocation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.group.carbon.service.allocation.LpSolver.Dir.*;
import static org.junit.jupiter.api.Assertions.*;

class LpSolverTest {

  private LpSolver.Constraint c(double[] a, LpSolver.Dir dir, double b) {
    return new LpSolver.Constraint(a, dir, b);
  }

  @Test
  void simpleLeProblem() {
    // max 3x+4y ≡ min -3x-4y ; x+2y<=14, 3x+2y<=18, x,y>=0 → 最优 x=2,y=6
    LpSolver solver = new LpSolver();
    LpSolver.Result r = solver.minimize(new double[]{-3, -4}, new LpSolver.Constraint[]{
        c(new double[]{1, 2}, LE, 14),
        c(new double[]{3, 2}, LE, 18)});
    assertTrue(r.feasible, r.note);
    assertEquals(2.0, r.x[0], 1e-6);
    assertEquals(6.0, r.x[1], 1e-6);
  }

  @Test
  void equalityAndGeConstraints() {
    // x+y=10, x>=3, y<=8, min x → x=3, y=7
    LpSolver solver = new LpSolver();
    LpSolver.Result r = solver.minimize(new double[]{1, 0}, new LpSolver.Constraint[]{
        c(new double[]{1, 1}, EQ, 10),
        c(new double[]{1, 0}, GE, 3),
        c(new double[]{0, 1}, LE, 8)});
    assertTrue(r.feasible, r.note);
    assertEquals(3.0, r.x[0], 1e-6);
    assertEquals(7.0, r.x[1], 1e-6);
  }

  @Test
  void negativeRhsIsNormalized() {
    // -x >= -5 即 x<=5；min -x → x=5
    LpSolver solver = new LpSolver();
    LpSolver.Result r = solver.minimize(new double[]{-1}, new LpSolver.Constraint[]{
        c(new double[]{-1}, GE, -5)});
    assertTrue(r.feasible, r.note);
    assertEquals(5.0, r.x[0], 1e-6);
  }

  @Test
  void infeasibleDetected() {
    // x>=10 且 x<=5 → 无解
    LpSolver solver = new LpSolver();
    LpSolver.Result r = solver.minimize(new double[]{1}, new LpSolver.Constraint[]{
        c(new double[]{1}, GE, 10),
        c(new double[]{1}, LE, 5)});
    assertFalse(r.feasible);
  }

  @Test
  void allocationStyleFeasibleRanges() {
    // 模拟平衡分配：总表 100，两条产品线子表读数 40/55（各 ±5%），产能上界 60/60
    // 守恒 x1+x2=100；38<=x1<=42；x2<=60 → 守恒把 x1 下界顶到 40，最终 x1∈[40,42]
    List<LpSolver.Constraint> cs = new ArrayList<>();
    cs.add(c(new double[]{1, 1}, EQ, 100));
    cs.add(c(new double[]{1, 0}, LE, 60));   // 产能
    cs.add(c(new double[]{0, 1}, LE, 60));
    cs.add(c(new double[]{1, 0}, GE, 38));   // 子表误差下界
    cs.add(c(new double[]{1, 0}, LE, 42));   // 子表误差上界
    LpSolver solver = new LpSolver();
    LpSolver.Result minX1 = solver.minimize(new double[]{1, 0}, cs.toArray(LpSolver.Constraint[]::new));
    LpSolver.Result maxX1 = solver.minimize(new double[]{-1, 0}, cs.toArray(LpSolver.Constraint[]::new));
    assertTrue(minX1.feasible && maxX1.feasible);
    assertEquals(40.0, minX1.x[0], 1e-6);
    assertEquals(42.0, maxX1.x[0], 1e-6);
  }
}
