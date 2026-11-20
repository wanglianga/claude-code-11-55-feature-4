package com.group.carbon.service.allocation;

/**
 * 两阶段单纯形线性规划求解器（tableau + Bland 规则）。
 * 用于平衡分配法：在总表守恒、子表误差界、工程产能约束下求各产品线分配量的可行区间。
 *
 * 标准形：min c·x，约束 A x {≤,=,≥} b，变量下界 0；变量的有限上下界以额外 ≤ 约束表达。
 * 规模很小（变量数 = 产品线数，约束数十条以内），double 精度足够，结果落库前统一保留 3 位小数。
 */
public class LpSolver {
  private static final double EPS = 1e-9;

  /** 约束方向 */
  public enum Dir { LE, EQ, GE }

  public static class Constraint {
    final double[] a;
    final Dir dir;
    final double b;
    public Constraint(double[] a, Dir dir, double b) { this.a = a; this.dir = dir; this.b = b; }
  }

  public static class Result {
    public final boolean feasible;
    public final double[] x;
    public final String note;
    Result(boolean feasible, double[] x, String note) {
      this.feasible = feasible; this.x = x; this.note = note;
    }
  }

  /** 求 min c·x。无解返回 feasible=false。 */
  public Result minimize(double[] c, Constraint[] constraints) {
    int n = c.length;
    int slack = 0, surplus = 0, artificial = 0;
    for (Constraint cn : constraints) {
      if (cn.dir == Dir.LE) slack++;
      else if (cn.dir == Dir.GE) { surplus++; artificial++; }
      else artificial++;
    }
    int firstSlack = n;
    int firstSurplus = n + slack;
    int firstArtificial = n + slack + surplus;
    int cols = n + slack + surplus + artificial;
    int m = constraints.length;

    double[][] tab = new double[m][cols + 1];
    int[] basis = new int[m];
    int sl = firstSlack, su = firstSurplus, ar = firstArtificial;
    for (int i = 0; i < m; i++) {
      Constraint cn = constraints[i];
      // RHS 统一为非负；为负时整行乘 -1 并翻转方向
      double sign = cn.b < -EPS ? -1 : 1;
      for (int j = 0; j < n; j++) tab[i][j] = cn.a[j] * sign;
      double rhs = cn.b * sign;
      Dir dir = sign < 0 ? flip(cn.dir) : cn.dir;
      if (dir == Dir.LE) {
        tab[i][sl] = 1; basis[i] = sl; sl++;
      } else if (dir == Dir.GE) {
        tab[i][su] = -1; su++;
        tab[i][ar] = 1; basis[i] = ar; ar++;
      } else {
        tab[i][ar] = 1; basis[i] = ar; ar++;
      }
      tab[i][cols] = Math.max(rhs, 0);
    }

    // ---------- 第一阶段：min 人工变量之和 ----------
    double[] obj = new double[cols + 1];
    for (int i = 0; i < m; i++) {
      if (basis[i] >= firstArtificial) {
        for (int j = 0; j <= cols; j++) obj[j] -= tab[i][j];
      }
    }
    if (iterate(tab, basis, obj, cols, firstArtificial)) {
      return new Result(false, null, "模型无界：缺少有效上界约束，请检查表计误差界与工程产能参数");
    }
    // 第一阶段目标行 RHS = -w（w 为人工变量之和）；w>0 即无可行解
    double artificialSum = -obj[cols];
    if (artificialSum > 1e-6) {
      return new Result(false, null, "约束无解：子表误差下界与产能/守恒约束不相容（人工剩余 " + round6(artificialSum) + "）");
    }
    // 值为 0 的人工变量仍在基中：用非人工系数换出；整行为 0 说明是冗余约束，保留零行不影响后续
    for (int i = 0; i < m; i++) {
      if (basis[i] >= firstArtificial) {
        for (int j = 0; j < firstArtificial; j++) {
          if (Math.abs(tab[i][j]) > EPS) { pivot(tab, basis, i, j); break; }
        }
      }
    }

    // ---------- 第二阶段：真实目标 ----------
    obj = new double[cols + 1];
    System.arraycopy(c, 0, obj, 0, n);
    for (int i = 0; i < m; i++) {
      int bv = basis[i];
      if (bv < n && Math.abs(obj[bv]) > EPS) {
        double coef = obj[bv];
        for (int j = 0; j <= cols; j++) obj[j] -= coef * tab[i][j];
      }
    }
    if (iterate(tab, basis, obj, cols, firstArtificial)) {
      return new Result(false, null, "模型无界：缺少有效上界约束，请检查表计误差界与工程产能参数");
    }
    double[] x = new double[n];
    for (int i = 0; i < m; i++) {
      if (basis[i] < n) x[basis[i]] = Math.max(0, tab[i][cols]);
    }
    return new Result(true, x, "OK");
  }

  private static Dir flip(Dir d) {
    return d == Dir.LE ? Dir.GE : d == Dir.GE ? Dir.LE : Dir.EQ;
  }

  /**
   * 单纯形迭代（Bland 规则）。返回 true 表示无界。
   * 第一阶段人工列（列下标 ≥ artificialStart）不参与进基。
   */
  private boolean iterate(double[][] tab, int[] basis, double[] obj, int cols, int artificialStart) {
    int m = tab.length;
    while (true) {
      int enter = -1;
      int limit = artificialStart > 0 ? artificialStart : cols;
      for (int j = 0; j < limit; j++) {
        if (obj[j] < -EPS) { enter = j; break; } // Bland：最小下标进基
      }
      if (enter < 0) return false;
      int row = -1;
      double bestRatio = Double.MAX_VALUE;
      for (int i = 0; i < m; i++) {
        if (tab[i][enter] > EPS) {
          double ratio = tab[i][cols] / tab[i][enter];
          if (ratio < bestRatio - EPS
              || (Math.abs(ratio - bestRatio) <= EPS && (row < 0 || basis[i] < basis[row]))) {
            bestRatio = ratio;
            row = i;
          }
        }
      }
      if (row < 0) return true; // 无界
      pivot(tab, basis, row, enter);
      double coef = obj[enter];
      for (int j = 0; j <= cols; j++) obj[j] -= coef * tab[row][j];
    }
  }

  private static void pivot(double[][] tab, int[] basis, int row, int enter) {
    int cols = tab[0].length;
    double div = tab[row][enter];
    for (int j = 0; j < cols; j++) tab[row][j] /= div;
    for (int i = 0; i < tab.length; i++) {
      if (i == row) continue;
      double factor = tab[i][enter];
      if (Math.abs(factor) <= EPS) continue;
      for (int j = 0; j < cols; j++) tab[i][j] -= factor * tab[row][j];
    }
    basis[row] = enter;
  }

  private static double round6(double v) { return Math.round(v * 1e6) / 1e6; }
}
