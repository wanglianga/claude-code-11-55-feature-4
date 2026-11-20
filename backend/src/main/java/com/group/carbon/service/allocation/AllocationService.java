package com.group.carbon.service.allocation;

import com.group.carbon.common.BizException;
import com.group.carbon.common.EnergyTypes;
import com.group.carbon.common.Types.*;
import com.group.carbon.dto.Requests.*;
import com.group.carbon.model.*;
import com.group.carbon.repo.*;
import com.group.carbon.service.RunService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 共用资源分配服务（蒸汽 / 压缩空气）。
 *
 * 两种主口径（同为正式口径，平台不预设主次，由表计覆盖、误差证据与客户区间接受度决定）：
 * - HIERARCHY 等级法：依次使用 子表 → 校准系数（×工时）→ 工时；证据不足的部分保留为未分配量，
 *   来源含义清楚、客户可逐步复算；子表申领总量超过总表时直接阻断（409）。
 * - BALANCE 平衡法：把总表守恒等式、子表校准误差界、工程产能上界共同纳入线性规划，
 *   求出每条产品线分配量的可行区间 [lo,hi]；无可行解时禁止固定，并给出求解解释。
 *
 * 全部结果满足守恒：已分配 + 未分配 = 总表总量；每条分配都落证据快照，可回查复算。
 */
@Service
public class AllocationService {
  private static final BigDecimal SCALE = new BigDecimal("0.001");
  private static final BigDecimal TOL = new BigDecimal("0.001");

  private final AllocationRunRepo runRepo;
  private final AllocationRecordRepo recordRepo;
  private final AllocationEvidenceRepo evidenceRepo;
  private final UtilityMeterRepo meterRepo;
  private final EngineeringCoefficientRepo coefRepo;
  private final WorkHourRepo workHourRepo;
  private final SourceRowRepo rowRepo;
  private final PlantRepo plantRepo;
  private final ProductLineRepo lineRepo;

  public AllocationService(AllocationRunRepo runRepo, AllocationRecordRepo recordRepo,
                           AllocationEvidenceRepo evidenceRepo, UtilityMeterRepo meterRepo,
                           EngineeringCoefficientRepo coefRepo, WorkHourRepo workHourRepo,
                           SourceRowRepo rowRepo, PlantRepo plantRepo, ProductLineRepo lineRepo) {
    this.runRepo = runRepo;
    this.recordRepo = recordRepo;
    this.evidenceRepo = evidenceRepo;
    this.meterRepo = meterRepo;
    this.coefRepo = coefRepo;
    this.workHourRepo = workHourRepo;
    this.rowRepo = rowRepo;
    this.plantRepo = plantRepo;
    this.lineRepo = lineRepo;
  }

  // ====================================================================
  // 基础数据维护：表计 / 工程系数 / 工时
  // ====================================================================

  @Transactional
  public UtilityMeter createMeter(String username, MeterReq req) {
    EnergyTypes.metaOf(req.energyType());
    if (!EnergyTypes.isSharedUtility(req.energyType())) {
      throw BizException.badRequest("表计仅支持共用资源类型 STEAM / COMP_AIR");
    }
    plantRepo.findById(req.plantId()).orElseThrow(() -> BizException.badRequest("工厂不存在: " + req.plantId()));
    MeterRole role = parseRole(req.role());
    Long reqLineId = req.productLineId();
    Long lineId;
    if (role == MeterRole.SUB) {
      if (reqLineId == null) throw BizException.badRequest("子表必须归属一条产品线");
      final Long checkLineId = reqLineId;
      lineRepo.findById(checkLineId).orElseThrow(() -> BizException.badRequest("产品线不存在: " + checkLineId));
      lineId = reqLineId;
    } else {
      lineId = null; // 总表不归属产品线
    }
    if (meterRepo.existsByMeterNo(req.meterNo())) {
      throw BizException.conflict("表计编号已存在: " + req.meterNo());
    }
    UtilityMeter m = new UtilityMeter();
    m.setMeterNo(req.meterNo());
    m.setPlantId(req.plantId());
    m.setEnergyType(req.energyType());
    m.setRole(role);
    m.setProductLineId(lineId);
    m.setErrorPct(req.errorPct() != null && req.errorPct().signum() >= 0 ? req.errorPct() : BigDecimal.ZERO);
    m.setCalibrationRef(req.calibrationRef());
    m.setRemark(req.remark());
    return meterRepo.save(m);
  }

  @Transactional
  public UtilityMeter retireMeter(Long id, String reason) {
    UtilityMeter m = meterRepo.findById(id).orElseThrow(() -> BizException.notFound("表计不存在: " + id));
    m.setStatus(MeterStatus.RETIRED);
    m.setRemark(trim(nz(m.getRemark()) + "｜停用原因：" + nz(reason)));
    return meterRepo.save(m);
  }

  public List<UtilityMeter> listMeters(Long plantId, String energyType) {
    if (plantId != null && energyType != null) {
      return meterRepo.findByPlantIdAndEnergyTypeOrderByRoleAscIdAsc(plantId, energyType);
    }
    return meterRepo.findAll();
  }

  @Transactional
  public EngineeringCoefficient createCoefficient(String username, CoefficientReq req) {
    RunService.validatePeriod(req.validFrom());
    if (req.validTo() != null && !req.validTo().isBlank()) RunService.validatePeriod(req.validTo());
    plantRepo.findById(req.plantId()).orElseThrow(() -> BizException.badRequest("工厂不存在: " + req.plantId()));
    lineRepo.findById(req.productLineId()).orElseThrow(() -> BizException.badRequest("产品线不存在: " + req.productLineId()));
    if (!EnergyTypes.isSharedUtility(req.energyType())) {
      throw BizException.badRequest("工程系数仅支持共用资源类型 STEAM / COMP_AIR");
    }
    if (req.coefficient().signum() <= 0) throw BizException.badRequest("系数必须为正数");
    EngineeringCoefficient c = new EngineeringCoefficient();
    c.setPlantId(req.plantId());
    c.setProductLineId(req.productLineId());
    c.setEnergyType(req.energyType());
    c.setCoefficient(req.coefficient());
    c.setBasis(req.basis());
    c.setValidFrom(req.validFrom());
    c.setValidTo(req.validTo() != null && !req.validTo().isBlank() ? req.validTo() : null);
    c.setStatus(MeterStatus.ACTIVE);
    c.setCreatedBy(username);
    return coefRepo.save(c);
  }

  public List<EngineeringCoefficient> listCoefficients(Long plantId, String energyType) {
    if (plantId != null && energyType != null) {
      return coefRepo.findByPlantIdAndEnergyTypeOrderByIdAsc(plantId, energyType);
    }
    return coefRepo.findAll();
  }

  /** 工艺改造：封死旧系数适用期并停用，历史批次仍可回查当时依据。 */
  @Transactional
  public EngineeringCoefficient retireCoefficient(Long id, String validTo) {
    EngineeringCoefficient c = coefRepo.findById(id)
        .orElseThrow(() -> BizException.notFound("工程系数不存在: " + id));
    if (validTo != null && !validTo.isBlank()) {
      RunService.validatePeriod(validTo);
      c.setValidTo(validTo);
    }
    c.setStatus(MeterStatus.RETIRED);
    return coefRepo.save(c);
  }

  @Transactional
  public WorkHour createWorkHour(WorkHourReq req) {
    RunService.validatePeriod(req.period());
    plantRepo.findById(req.plantId()).orElseThrow(() -> BizException.badRequest("工厂不存在: " + req.plantId()));
    lineRepo.findById(req.productLineId()).orElseThrow(() -> BizException.badRequest("产品线不存在: " + req.productLineId()));
    if (!EnergyTypes.isSharedUtility(req.energyType())) {
      throw BizException.badRequest("工时台账仅支持共用资源类型 STEAM / COMP_AIR");
    }
    if (req.hours().signum() < 0) throw BizException.badRequest("工时不能为负数");
    Optional<WorkHour> existing = workHourRepo
        .findByPlantIdAndProductLineIdAndPeriodAndEnergyTypeAndStatus(
            req.plantId(), req.productLineId(), req.period(), req.energyType(), RowStatus.ACTIVE);
    if (existing.isPresent()) {
      throw BizException.conflict("该工厂/产品线/期间/资源已存在工时台账：" + existing.get().getSourceRef());
    }
    WorkHour w = new WorkHour();
    w.setPlantId(req.plantId());
    w.setProductLineId(req.productLineId());
    w.setEnergyType(req.energyType());
    w.setPeriod(req.period());
    w.setHours(req.hours());
    w.setSourceRef(req.sourceRef());
    w.setStatus(RowStatus.ACTIVE);
    return workHourRepo.save(w);
  }

  public List<WorkHour> listWorkHours(Long plantId, String period) {
    if (plantId != null && period != null) return workHourRepo.findByPlantIdAndPeriodOrderByIdAsc(plantId, period);
    return workHourRepo.findAll();
  }

  // ====================================================================
  // 分配运行
  // ====================================================================

  /** 期间内已核验批次的共用资源读数上下文。 */
  private class Context {
    BigDecimal total = null;
    SourceRow mainRow = null;
    UtilityMeter mainMeter = null;
    final Map<Long, SubReading> subs = new LinkedHashMap<>();
    final List<SourceRow> allRows = new ArrayList<>();
  }
  private record SubReading(UtilityMeter meter, SourceRow row, BigDecimal qty) {}

  @Transactional
  public AllocationRun createRun(String username, AllocationRunReq req) {
    RunService.validatePeriod(req.period());
    Plant plant = plantRepo.findById(req.plantId())
        .orElseThrow(() -> BizException.badRequest("工厂不存在: " + req.plantId()));
    if (!EnergyTypes.isSharedUtility(req.energyType())) {
      throw BizException.badRequest("分配运行仅支持共用资源类型 STEAM / COMP_AIR");
    }
    AllocationMethod method;
    try {
      method = AllocationMethod.valueOf(req.method());
    } catch (IllegalArgumentException e) {
      throw BizException.badRequest("分配口径必须为 HIERARCHY 或 BALANCE");
    }
    Context ctx = loadContext(req.plantId(), req.energyType(), req.period());
    if (ctx.total == null) {
      throw BizException.badRequest("期间 " + req.period() + " 缺少总表读数，无法建立守恒锚点，请先上传总表批次");
    }

    AllocationRun run = new AllocationRun();
    run.setRunNo(RunService.newNo("ALLOC"));
    run.setPlantId(plant.getId());
    run.setEnergyType(req.energyType());
    run.setPeriod(req.period());
    run.setMethod(method);
    run.setStatus(AllocationStatus.DRAFT);
    run.setTotalQty(ctx.total);
    run.setCreatedBy(username);
    run.setChangeNote(req.changeNote());
    runRepo.save(run);

    if (method == AllocationMethod.HIERARCHY) {
      runHierarchy(run, ctx);
    } else {
      runBalance(run, ctx);
    }
    runRepo.save(run);

    if (Boolean.TRUE.equals(req.fix())) {
      doFix(run, username, req.changeNote());
    }
    return runRepo.findById(run.getId()).orElseThrow();
  }

  private Context loadContext(Long plantId, String energyType, String period) {
    Context ctx = new Context();
    List<SourceRow> rows = rowRepo.findActiveVerifiedByPeriod(period, RowStatus.ACTIVE).stream()
        .filter(r -> r.getPlantId().equals(plantId) && energyType.equals(r.getEnergyType()))
        .toList();
    ctx.allRows.addAll(rows);
    for (SourceRow row : rows) {
      UtilityMeter meter = row.getMeterId() != null ? meterRepo.findById(row.getMeterId()).orElse(null) : null;
      if (meter == null) {
        throw BizException.badRequest("来源行 " + row.getId() + " 未关联表计，无法进入分配证据链");
      }
      if (meter.getRole() == MeterRole.MAIN) {
        if (ctx.total != null) {
          throw BizException.conflict("同一期间存在多块总表读数（" + ctx.mainMeter.getMeterNo()
              + " 与 " + meter.getMeterNo() + "），守恒锚点不唯一");
        }
        ctx.total = row.getQuantity();
        ctx.mainRow = row;
        ctx.mainMeter = meter;
      } else {
        if (meter.getProductLineId() == null) {
          throw BizException.badRequest("子表 " + meter.getMeterNo() + " 未归属产品线");
        }
        ctx.subs.merge(meter.getProductLineId(), new SubReading(meter, row, row.getQuantity()),
            (a, b) -> { throw BizException.conflict("产品线 " + meter.getProductLineId() + " 存在多块子表读数"); });
      }
    }
    return ctx;
  }

  // ---------------- 等级法 ----------------

  private void runHierarchy(AllocationRun run, Context ctx) {
    List<Long> lineIds = lineRepo.findAll().stream().map(ProductLine::getId).sorted().toList();
    BigDecimal subSum = ctx.subs.values().stream().map(SubReading::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
    // 两个产品同时申领同一蒸汽量：子表申领合计超过总表 → 阻断，绝不产生超总量结果
    if (subSum.compareTo(ctx.total) > 0) {
      throw BizException.conflict("子表申领总量 " + subSum.toPlainString() + " 超过总表总量 "
          + ctx.total.toPlainString() + "，守恒不成立，已阻断该分配结果");
    }

    Map<Long, BigDecimal> decided = new LinkedHashMap<>();
    Map<Long, EvidenceGrade> grades = new LinkedHashMap<>();
    // 等级 1：子表
    for (Map.Entry<Long, SubReading> e : ctx.subs.entrySet()) {
      decided.put(e.getKey(), e.getValue().qty());
      grades.put(e.getKey(), EvidenceGrade.SUB_METER);
    }
    BigDecimal remaining = ctx.total.subtract(subSum);

    // 等级 2：校准系数 × 工时（仅对无子表的产品线）
    Map<Long, BigDecimal> coefEst = new LinkedHashMap<>();
    Map<Long, EngineeringCoefficient> coefUsed = new LinkedHashMap<>();
    Map<Long, WorkHour> hourUsed = new LinkedHashMap<>();
    for (Long lineId : lineIds) {
      if (decided.containsKey(lineId)) continue;
      EngineeringCoefficient c = activeCoefficient(run.getPlantId(), lineId, run.getEnergyType(), run.getPeriod());
      Optional<WorkHour> wh = workHourRepo.findByPlantIdAndProductLineIdAndPeriodAndEnergyTypeAndStatus(
          run.getPlantId(), lineId, run.getPeriod(), run.getEnergyType(), RowStatus.ACTIVE);
      if (c != null && wh.isPresent()) {
        BigDecimal est = wh.get().getHours().multiply(c.getCoefficient());
        coefEst.put(lineId, est);
        coefUsed.put(lineId, c);
        hourUsed.put(lineId, wh.get());
      }
    }
    BigDecimal coefSum = coefEst.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal scaleFactor = BigDecimal.ONE;
    if (coefSum.signum() > 0 && coefSum.compareTo(remaining) > 0) {
      // 系数推算总量超过总表余量：等比缩放并在证据中说明，保证守恒不被突破
      scaleFactor = remaining.divide(coefSum, 6, RoundingMode.HALF_UP);
    }
    for (Map.Entry<Long, BigDecimal> e : coefEst.entrySet()) {
      BigDecimal q = e.getValue().multiply(scaleFactor).setScale(3, RoundingMode.HALF_UP);
      decided.put(e.getKey(), q);
      grades.put(e.getKey(), EvidenceGrade.COEF);
      remaining = remaining.subtract(q);
    }

    // 等级 3：有实际工时但无适用校准系数 → 按工时占比分摊总表余量（依据是工时记录，不是产量平均）
    Map<Long, WorkHour> hourOnly = new LinkedHashMap<>();
    BigDecimal hourTotal = BigDecimal.ZERO;
    for (Long lineId : lineIds) {
      if (decided.containsKey(lineId)) continue;
      EngineeringCoefficient c = activeCoefficient(run.getPlantId(), lineId, run.getEnergyType(), run.getPeriod());
      Optional<WorkHour> wh = workHourRepo.findByPlantIdAndProductLineIdAndPeriodAndEnergyTypeAndStatus(
          run.getPlantId(), lineId, run.getPeriod(), run.getEnergyType(), RowStatus.ACTIVE);
      if (c == null && wh.isPresent() && remaining.signum() > 0) {
        hourOnly.put(lineId, wh.get());
        hourTotal = hourTotal.add(wh.get().getHours());
      }
    }
    if (!hourOnly.isEmpty() && hourTotal.signum() > 0 && remaining.signum() > 0) {
      BigDecimal shared = BigDecimal.ZERO;
      List<Long> hourKeys = new ArrayList<>(hourOnly.keySet());
      for (int k = 0; k < hourKeys.size(); k++) {
        Long lineId = hourKeys.get(k);
        BigDecimal q;
        if (k == hourKeys.size() - 1) {
          // 末位吸收三位舍入残差，保证分配合计严格等于余量
          q = remaining.subtract(shared).max(BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP);
        } else {
          q = remaining.multiply(hourOnly.get(lineId).getHours())
              .divide(hourTotal, 3, RoundingMode.HALF_UP);
        }
        shared = shared.add(q);
        decided.put(lineId, q);
        grades.put(lineId, EvidenceGrade.WORK_HOUR);
      }
      remaining = remaining.subtract(shared);
    }

    // 守恒残差校正：等比缩放后的三位舍入可能产生 ±0.01 内残差，
    // 在最后一条推算记录（系数→工时顺序）上双向校正（不为负），保证 已分配+未分配=总表 严格成立
    List<Long> adjustable = new ArrayList<>();
    adjustable.addAll(coefEst.keySet());
    adjustable.addAll(hourOnly.keySet());
    BigDecimal sumDecided = decided.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(3, RoundingMode.HALF_UP);
    BigDecimal residual = ctx.total.subtract(sumDecided).setScale(3, RoundingMode.HALF_UP);
    String adjustNote = null;
    Long adjustLine = adjustable.isEmpty() ? null : adjustable.get(adjustable.size() - 1);
    if (adjustLine != null && residual.abs().compareTo(new BigDecimal("0.01")) <= 0 && residual.signum() != 0) {
      BigDecimal before = decided.get(adjustLine);
      BigDecimal after = before.add(residual).max(BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP);
      decided.put(adjustLine, after);
      adjustNote = "守恒舍入校正：" + before.toPlainString() + " → " + after.toPlainString();
      residual = ctx.total.subtract(decided.values().stream()
          .reduce(BigDecimal.ZERO, BigDecimal::add)).setScale(3, RoundingMode.HALF_UP);
    }

    // 落分配记录与证据
    BigDecimal allocated = BigDecimal.ZERO;
    for (Long lineId : lineIds) {
      BigDecimal q = decided.getOrDefault(lineId, BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP);
      if (q.signum() == 0) continue;
      EvidenceGrade grade = grades.get(lineId);
      AllocationRecord rec = saveRecord(run.getId(), lineId, q, q, q, grade, BigDecimal.ZERO);
      allocated = allocated.add(q);
      if (grade == EvidenceGrade.SUB_METER) {
        SubReading sr = ctx.subs.get(lineId);
        saveEvidence(run.getId(), rec.getId(), EvidenceGrade.SUB_METER, sr.meter().getId(),
            sr.row().getId(), null, null,
            "子表 " + sr.meter().getMeterNo() + " 直接计量（校准证书 "
                + nz(sr.meter().getCalibrationRef()) + "）",
            sr.qty(), q, "子表读数直接归属，证据等级最高；误差界 ±" + sr.meter().getErrorPct().toPlainString() + "%");
      } else if (grade == EvidenceGrade.COEF) {
        EngineeringCoefficient c = coefUsed.get(lineId);
        WorkHour w = hourUsed.get(lineId);
        String detail = "工时 " + w.getHours().toPlainString() + " × 系数 " + c.getCoefficient().toPlainString()
            + " = " + coefEst.get(lineId).setScale(3, RoundingMode.HALF_UP).toPlainString();
        if (scaleFactor.compareTo(BigDecimal.ONE) != 0) {
          detail += "；推算总量超过总表余量，按系数 " + scaleFactor.setScale(4, RoundingMode.HALF_UP)
              + " 等比缩放后计入";
        }
        if (adjustNote != null && lineId.equals(adjustLine)) {
          detail += "；" + adjustNote;
        }
        saveEvidence(run.getId(), rec.getId(), EvidenceGrade.COEF, null, null, c.getId(), w.getId(),
            "工程系数 " + c.getId() + "（" + nz(c.getBasis()) + "）× 工时单 " + nz(w.getSourceRef()),
            coefEst.get(lineId), q, detail);
      } else {
        // WORK_HOUR：无适用系数，按该产品线工时占总工时比例分摊总表余量
        WorkHour w = hourOnly.get(lineId);
        BigDecimal ratio = w.getHours().divide(hourTotal, 6, RoundingMode.HALF_UP);
        String detail = "无适用校准系数，按工时占比 " + ratio.toPlainString() + "（工时 "
            + w.getHours().toPlainString() + " / 合计 " + hourTotal.toPlainString()
            + "）分摊总表余量，依据为工时记录单而非产量平均";
        if (adjustNote != null && lineId.equals(adjustLine)) {
          detail += "；" + adjustNote;
        }
        saveEvidence(run.getId(), rec.getId(), EvidenceGrade.WORK_HOUR, null, null, null, w.getId(),
            "工时单 " + nz(w.getSourceRef()) + "（" + w.getHours().toPlainString() + " 工时）",
            w.getHours(), q, detail);
      }
    }

    // 未分配：无子表、无（系数+工时）且无单独工时的产品线；总表余量整体保留为未分配
    List<String> missing = new ArrayList<>();
    for (Long lineId : lineIds) {
      if (decided.containsKey(lineId)) continue;
      EngineeringCoefficient c = activeCoefficient(run.getPlantId(), lineId, run.getEnergyType(), run.getPeriod());
      Optional<WorkHour> wh = workHourRepo.findByPlantIdAndProductLineIdAndPeriodAndEnergyTypeAndStatus(
          run.getPlantId(), lineId, run.getPeriod(), run.getEnergyType(), RowStatus.ACTIVE);
      String reason = c == null
          ? (wh.isEmpty() ? "缺工程系数与工时台账" : "缺工程系数")
          : "缺工时台账";
      missing.add(lineName(lineId) + "（" + reason + "）");
      AllocationEvidence ev = new AllocationEvidence();
      ev.setAllocationRunId(run.getId());
      ev.setEvidenceGrade(EvidenceGrade.UNALLOCATED);
      ev.setRefLabel(lineName(lineId) + " 无可用分配证据");
      ev.setAppliedQty(BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP));
      ev.setDetail(reason + "，等级法不强行分摊，对应资源保留在未分配量中");
      evidenceRepo.save(ev);
    }
    BigDecimal unallocated = ctx.total.subtract(allocated).setScale(3, RoundingMode.HALF_UP);
    if (unallocated.signum() > 0) {
      AllocationEvidence pool = new AllocationEvidence();
      pool.setAllocationRunId(run.getId());
      pool.setEvidenceGrade(EvidenceGrade.UNALLOCATED);
      pool.setMeterId(ctx.mainMeter.getId());
      pool.setSourceRowId(ctx.mainRow.getId());
      pool.setRefLabel("总表 " + ctx.mainMeter.getMeterNo() + " 未分配余量");
      pool.setReadingQty(ctx.total);
      pool.setAppliedQty(unallocated);
      pool.setDetail("总表余量保持未分配，不按产量平均转移给其他型号"
          + (missing.isEmpty() ? "" : "；涉及产品线：" + String.join("、", missing)));
      evidenceRepo.save(pool);
    }

    run.setAllocatedQty(allocated);
    run.setUnallocatedQty(unallocated);
    run.setUncertaintyLow(allocated);
    run.setUncertaintyHigh(allocated);
    run.setConservationDiff(ctx.total.subtract(allocated).subtract(unallocated).setScale(3, RoundingMode.HALF_UP));
    run.setFeasible(true);
    run.setSolverNote("等级法：子表 " + ctx.subs.size() + " 条、系数推算 " + coefEst.size()
        + " 条、工时占比分摊 " + hourOnly.size() + " 条、未分配 "
        + unallocated.toPlainString() + " " + EnergyTypes.unitOf(run.getEnergyType())
        + (missing.isEmpty() ? "" : "；缺证据产品线：" + String.join("、", missing)));
  }

  // ---------------- 平衡法 ----------------

  private void runBalance(AllocationRun run, Context ctx) {
    List<Long> lineIds = lineRepo.findAll().stream().map(ProductLine::getId).sorted().toList();
    int n = lineIds.size();
    List<LpSolver.Constraint> constraints = new ArrayList<>();
    // 1) 总表守恒等式：Σx = T —— 从模型上排除任何超过总量的分配结果
    constraints.add(new LpSolver.Constraint(ones(n), LpSolver.Dir.EQ, ctx.total.doubleValue()));

    Map<Long, Integer> idx = new LinkedHashMap<>();
    for (int i = 0; i < n; i++) idx.put(lineIds.get(i), i);

    List<String> noteParts = new ArrayList<>();
    noteParts.add("总表 " + ctx.mainMeter.getMeterNo() + " 守恒等式 Σx=" + ctx.total.toPlainString()
        + " " + EnergyTypes.unitOf(run.getEnergyType()));

    // 2) 子表误差界
    for (Map.Entry<Long, SubReading> e : ctx.subs.entrySet()) {
      int i = idx.get(e.getKey());
      SubReading sr = e.getValue();
      BigDecimal ePct = sr.meter().getErrorPct().divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
      double lo = sr.qty().multiply(BigDecimal.ONE.subtract(ePct)).doubleValue();
      double hi = sr.qty().multiply(BigDecimal.ONE.add(ePct)).doubleValue();
      double[] col = unitVec(n, i);
      constraints.add(new LpSolver.Constraint(col, LpSolver.Dir.GE, Math.max(0, lo)));
      constraints.add(new LpSolver.Constraint(col, LpSolver.Dir.LE, hi));
      noteParts.add("子表 " + sr.meter().getMeterNo() + "→" + lineName(e.getKey()) + " 读数 "
          + sr.qty().toPlainString() + "（±" + sr.meter().getErrorPct().toPlainString() + "%）→ x∈["
          + fmt(lo) + "," + fmt(hi) + "]");
    }

    // 3) 工程产能上界：系数 × 工时
    Map<Long, BigDecimal> capacity = new LinkedHashMap<>();
    for (Long lineId : lineIds) {
      EngineeringCoefficient c = activeCoefficient(run.getPlantId(), lineId, run.getEnergyType(), run.getPeriod());
      Optional<WorkHour> wh = workHourRepo.findByPlantIdAndProductLineIdAndPeriodAndEnergyTypeAndStatus(
          run.getPlantId(), lineId, run.getPeriod(), run.getEnergyType(), RowStatus.ACTIVE);
      if (c != null && wh.isPresent()) {
        BigDecimal cap = wh.get().getHours().multiply(c.getCoefficient());
        capacity.put(lineId, cap);
        constraints.add(new LpSolver.Constraint(unitVec(n, idx.get(lineId)), LpSolver.Dir.LE, cap.doubleValue()));
        noteParts.add("产能上界 " + lineName(lineId) + "：系数 " + c.getCoefficient().toPlainString()
            + " × 工时 " + wh.get().getHours().toPlainString() + " = " + cap.setScale(3, RoundingMode.HALF_UP));
      }
    }

    LpSolver solver = new LpSolver();
    LpSolver.Constraint[] arr = constraints.toArray(LpSolver.Constraint[]::new);
    LpSolver.Result probe = solver.minimize(new double[n], arr);
    if (!probe.feasible) {
      run.setFeasible(false);
      run.setAllocatedQty(BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP));
      run.setUnallocatedQty(ctx.total.setScale(3, RoundingMode.HALF_UP));
      run.setConservationDiff(ctx.total.setScale(3, RoundingMode.HALF_UP));
      run.setUncertaintyLow(BigDecimal.ZERO);
      run.setUncertaintyHigh(BigDecimal.ZERO);
      run.setSolverNote("平衡求解无可行解：" + probe.note + "；约束：" + String.join("；", noteParts));
      return;
    }

    // 逐变量求可行区间
    BigDecimal[] los = new BigDecimal[n], his = new BigDecimal[n], mids = new BigDecimal[n];
    BigDecimal sumLo = BigDecimal.ZERO, sumMid = BigDecimal.ZERO;
    for (int i = 0; i < n; i++) {
      LpSolver.Result rMin = solver.minimize(unitVec(n, i), arr);
      LpSolver.Result rMax = solver.minimize(negUnitVec(n, i), arr);
      if (!rMin.feasible || !rMax.feasible) {
        run.setFeasible(false);
        run.setSolverNote("区间求解失败：" + rMin.note + " / " + rMax.note);
        return;
      }
      los[i] = bd(rMin.x[i]);
      his[i] = bd(rMax.x[i]);
      mids[i] = los[i].add(his[i]).divide(new BigDecimal("2"), 3, RoundingMode.HALF_UP);
      sumLo = sumLo.add(los[i]);
      sumMid = sumMid.add(mids[i]);
    }

    // 点值取区间中点，并把与总表的残差在各区间内消化，保证 Σ点值=总表（守恒、零未分配）
    BigDecimal[] points = mids.clone();
    BigDecimal residual = ctx.total.subtract(sumMid).setScale(3, RoundingMode.HALF_UP);
    distributeResidual(points, los, his, residual);

    BigDecimal allocated = BigDecimal.ZERO, sumHi = BigDecimal.ZERO;
    for (int i = 0; i < n; i++) {
      if (points[i].signum() == 0 && los[i].signum() == 0 && his[i].signum() == 0) continue;
      Long lineId = lineIds.get(i);
      BigDecimal half = his[i].subtract(los[i]).divide(new BigDecimal("2"), 3, RoundingMode.HALF_UP);
      AllocationRecord rec = saveRecord(run.getId(), lineId, points[i], los[i], his[i],
          EvidenceGrade.BALANCE, half);
      allocated = allocated.add(points[i]);
      sumHi = sumHi.add(his[i]);

      // 证据快照：子表读数 / 系数+工时 / 求解区间
      SubReading sr = ctx.subs.get(lineId);
      if (sr != null) {
        saveEvidence(run.getId(), rec.getId(), EvidenceGrade.BALANCE, sr.meter().getId(), sr.row().getId(),
            null, null, "子表 " + sr.meter().getMeterNo() + " 读数 " + sr.qty().toPlainString()
                + "（误差界 ±" + sr.meter().getErrorPct().toPlainString() + "%）",
            sr.qty(), points[i],
            "平衡约束：x∈[" + los[i].toPlainString() + "," + his[i].toPlainString() + "]");
      }
      EngineeringCoefficient c = activeCoefficient(run.getPlantId(), lineId, run.getEnergyType(), run.getPeriod());
      Optional<WorkHour> wh = workHourRepo.findByPlantIdAndProductLineIdAndPeriodAndEnergyTypeAndStatus(
          run.getPlantId(), lineId, run.getPeriod(), run.getEnergyType(), RowStatus.ACTIVE);
      if (c != null && wh.isPresent()) {
        saveEvidence(run.getId(), rec.getId(), EvidenceGrade.BALANCE, null, null, c.getId(), wh.get().getId(),
            "工程系数 × 工时（产能上界 " + capacity.get(lineId).setScale(3, RoundingMode.HALF_UP) + "）",
            capacity.get(lineId), points[i],
            "系数 " + c.getCoefficient().toPlainString() + " × 工时 " + wh.get().getHours().toPlainString()
                + "；可行域 x∈[" + los[i].toPlainString() + "," + his[i].toPlainString() + "]");
      }
      if (sr == null && (c == null || wh.isEmpty())) {
        AllocationEvidence ev = new AllocationEvidence();
        ev.setAllocationRunId(run.getId());
        ev.setAllocationRecordId(rec.getId());
        ev.setEvidenceGrade(EvidenceGrade.BALANCE);
        ev.setRefLabel(lineName(lineId) + " 无局部证据，仅受守恒等式与非负约束");
        ev.setAppliedQty(points[i]);
        ev.setDetail("平衡法借助总表守恒与其余产品线约束反解，可行域 x∈["
            + los[i].toPlainString() + "," + his[i].toPlainString() + "]");
        evidenceRepo.save(ev);
      }
    }

    BigDecimal unallocated = ctx.total.subtract(allocated).setScale(3, RoundingMode.HALF_UP);
    run.setFeasible(true);
    run.setAllocatedQty(allocated);
    run.setUnallocatedQty(unallocated);
    run.setUncertaintyLow(sumLo.setScale(3, RoundingMode.HALF_UP));
    run.setUncertaintyHigh(sumHi.setScale(3, RoundingMode.HALF_UP));
    run.setConservationDiff(ctx.total.subtract(allocated).subtract(unallocated).setScale(3, RoundingMode.HALF_UP));
    noteParts.add("求解结论：可行；各产品线区间见分配记录；点值取区间中点并按守恒等式校正，Σ点值=总表");
    run.setSolverNote(String.join("；", noteParts));
  }

  /** 在 [lo,hi] 内把残差分配给各变量（贪心），使 Σpoints 严格等于总表。 */
  private void distributeResidual(BigDecimal[] points, BigDecimal[] los, BigDecimal[] his, BigDecimal residual) {
    int guard = 0;
    while (residual.abs().compareTo(TOL) > 0 && guard++ < points.length * 4) {
      boolean progressed = false;
      for (int i = 0; i < points.length && residual.abs().compareTo(TOL) > 0; i++) {
        BigDecimal room = residual.signum() > 0 ? his[i].subtract(points[i]) : points[i].subtract(los[i]);
        if (room.signum() <= 0) continue;
        BigDecimal step = residual.abs().min(room).setScale(3, RoundingMode.HALF_UP);
        points[i] = points[i].add(residual.signum() > 0 ? step : step.negate());
        residual = residual.subtract(residual.signum() > 0 ? step : step.negate());
        progressed = true;
      }
      if (!progressed) break;
    }
  }

  // ---------------- 固定 / 查询 ----------------

  @Transactional
  public AllocationRun fix(Long runId, String username, String changeNote) {
    AllocationRun run = runRepo.findByIdForUpdate(runId)
        .orElseThrow(() -> BizException.notFound("分配运行不存在: " + runId));
    return doFix(run, username, changeNote);
  }

  private AllocationRun doFix(AllocationRun run, String username, String changeNote) {
    if (run.getStatus() != AllocationStatus.DRAFT) {
      throw BizException.conflict("只有草稿状态的分配运行可以固定，当前状态: " + run.getStatus());
    }
    if (!Boolean.TRUE.equals(run.getFeasible())) {
      throw BizException.conflict("平衡求解无可行解，不能固定为批次分配依据：" + nz(run.getSolverNote()));
    }
    BigDecimal check = run.getTotalQty().subtract(run.getAllocatedQty()).subtract(run.getUnallocatedQty());
    if (check.abs().compareTo(TOL) > 0) {
      throw BizException.conflict("守恒核对失败：总表 " + run.getTotalQty().toPlainString()
          + " ≠ 已分配 " + run.getAllocatedQty().toPlainString() + " + 未分配 "
          + run.getUnallocatedQty().toPlainString());
    }
    // 同工厂/资源/期间已有固定依据：旧依据标记被取代，批次历史仍指向旧依据（依据固定后不可改写）
    runRepo.findLatestFixed(run.getPlantId(), run.getEnergyType(), run.getPeriod()).ifPresent(prev -> {
      prev.setStatus(AllocationStatus.SUPERSEDED);
      prev.setSupersededByRunId(run.getId());
      if (changeNote != null && !changeNote.isBlank()) {
        prev.setChangeNote(trim(nz(prev.getChangeNote()) + "｜被新依据取代：" + changeNote));
      }
      runRepo.save(prev);
    });
    if (changeNote != null && !changeNote.isBlank()) run.setChangeNote(changeNote);
    run.setStatus(AllocationStatus.FIXED);
    run.setFixedBy(username);
    run.setFixedAt(java.time.LocalDateTime.now());
    return runRepo.save(run);
  }

  public List<AllocationRun> listRuns(String period, Long plantId, String energyType) {
    List<AllocationRun> all = runRepo.findAllByOrderByIdDesc();
    return all.stream()
        .filter(r -> period == null || period.isBlank() || r.getPeriod().equals(period))
        .filter(r -> plantId == null || plantId.equals(r.getPlantId()))
        .filter(r -> energyType == null || energyType.isBlank() || r.getEnergyType().equals(energyType))
        .toList();
  }

  public Map<String, Object> detail(Long id) {
    AllocationRun run = runRepo.findById(id)
        .orElseThrow(() -> BizException.notFound("分配运行不存在: " + id));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("run", run);
    List<AllocationRecord> records = recordRepo.findByAllocationRunIdOrderById(id);
    List<Map<String, Object>> recordViews = new ArrayList<>();
    Map<Long, String> names = lineRepo.findAll().stream()
        .collect(Collectors.toMap(ProductLine::getId, ProductLine::getName));
    for (AllocationRecord r : records) {
      Map<String, Object> v = new LinkedHashMap<>();
      v.put("record", r);
      v.put("productLineName", names.getOrDefault(r.getProductLineId(), String.valueOf(r.getProductLineId())));
      v.put("evidence", evidenceRepo.findByAllocationRecordIdOrderById(r.getId()));
      recordViews.add(v);
    }
    out.put("records", recordViews);
    out.put("evidence", evidenceRepo.findByAllocationRunIdOrderById(id));
    out.put("conservation", conservationView(run));
    return out;
  }

  /** 守恒核对视图：始终与共用资源总量核对。 */
  public Map<String, Object> conservationView(AllocationRun run) {
    Map<String, Object> v = new LinkedHashMap<>();
    String unit = EnergyTypes.unitOf(run.getEnergyType());
    v.put("unit", unit);
    v.put("totalQty", run.getTotalQty());
    v.put("allocatedQty", run.getAllocatedQty());
    v.put("unallocatedQty", run.getUnallocatedQty());
    v.put("sumCheck", run.getAllocatedQty().add(run.getUnallocatedQty()));
    v.put("diff", run.getConservationDiff());
    v.put("balanced", run.getConservationDiff().abs().compareTo(TOL) <= 0);
    v.put("uncertaintyLow", run.getUncertaintyLow());
    v.put("uncertaintyHigh", run.getUncertaintyHigh());
    return v;
  }

  // ====================================================================
  // 供核算运行调用：取最近固定依据
  // ====================================================================

  public Optional<AllocationRun> latestFixed(Long plantId, String energyType, String period) {
    return runRepo.findLatestFixed(plantId, energyType, period);
  }

  public List<AllocationRecord> recordsOf(Long allocationRunId) {
    return recordRepo.findByAllocationRunIdOrderById(allocationRunId);
  }

  public List<AllocationEvidence> evidenceOf(Long allocationRunId) {
    return evidenceRepo.findByAllocationRunIdOrderById(allocationRunId);
  }

  public AllocationRun findRun(Long id) {
    return runRepo.findById(id).orElse(null);
  }

  /** 分配运行对应的总表来源行（守恒锚点）；表计停用时历史读数仍可定位。 */
  public Optional<Long> mainRowId(Long allocationRunId) {
    AllocationRun run = runRepo.findById(allocationRunId).orElse(null);
    if (run == null) return Optional.empty();
    return mainRowsOf(run.getPlantId(), run.getEnergyType(), run.getPeriod()).stream()
        .findFirst().map(SourceRow::getId);
  }

  /** 某工厂/资源/期间的总表读数行（不排除已停用表计，改造前批次仍需回查）。 */
  public List<SourceRow> mainRowsOf(Long plantId, String energyType, String period) {
    Set<Long> mainMeterIds = meterRepo.findByPlantIdAndEnergyTypeOrderByRoleAscIdAsc(plantId, energyType).stream()
        .filter(m -> m.getRole() == MeterRole.MAIN)
        .map(UtilityMeter::getId).collect(Collectors.toSet());
    if (mainMeterIds.isEmpty()) return List.of();
    return rowRepo.findActiveVerifiedByPeriod(period, RowStatus.ACTIVE).stream()
        .filter(r -> r.getPlantId().equals(plantId) && energyType.equals(r.getEnergyType())
            && r.getMeterId() != null && mainMeterIds.contains(r.getMeterId()))
        .toList();
  }

  // ---------------- 辅助 ----------------

  private EngineeringCoefficient activeCoefficient(Long plantId, Long lineId, String energyType, String period) {
    return coefRepo.findByPlantIdAndProductLineIdAndEnergyTypeOrderByValidFromDesc(plantId, lineId, energyType)
        .stream()
        .filter(c -> c.getStatus() == MeterStatus.ACTIVE)
        .filter(c -> c.getValidFrom().compareTo(period) <= 0
            && (c.getValidTo() == null || c.getValidTo().compareTo(period) >= 0))
        .findFirst().orElse(null);
  }

  private AllocationRecord saveRecord(Long runId, Long lineId, BigDecimal q,
                                      BigDecimal lo, BigDecimal hi, EvidenceGrade grade, BigDecimal half) {
    AllocationRecord rec = new AllocationRecord(runId, lineId, q, lo, hi, grade, half);
    return recordRepo.save(rec);
  }

  private void saveEvidence(Long runId, Long recordId, EvidenceGrade grade, Long meterId, Long rowId,
                            Long coefId, Long workHourId, String label, BigDecimal reading,
                            BigDecimal applied, String detail) {
    AllocationEvidence ev = new AllocationEvidence();
    ev.setAllocationRunId(runId);
    ev.setAllocationRecordId(recordId);
    ev.setEvidenceGrade(grade);
    ev.setMeterId(meterId);
    ev.setSourceRowId(rowId);
    ev.setCoefficientId(coefId);
    ev.setWorkHourId(workHourId);
    ev.setRefLabel(label);
    ev.setReadingQty(reading != null ? reading.setScale(3, RoundingMode.HALF_UP) : null);
    ev.setAppliedQty(applied.setScale(3, RoundingMode.HALF_UP));
    ev.setDetail(detail);
    evidenceRepo.save(ev);
  }

  private static MeterRole parseRole(String role) {
    try {
      return MeterRole.valueOf(role);
    } catch (IllegalArgumentException e) {
      throw BizException.badRequest("表计角色必须为 MAIN 或 SUB");
    }
  }

  private static double[] ones(int n) {
    double[] a = new double[n];
    Arrays.fill(a, 1.0);
    return a;
  }

  private static double[] unitVec(int n, int i) {
    double[] a = new double[n];
    a[i] = 1.0;
    return a;
  }

  private static double[] negUnitVec(int n, int i) {
    double[] a = new double[n];
    a[i] = -1.0;
    return a;
  }

  private static BigDecimal bd(double v) {
    return BigDecimal.valueOf(Math.max(0, v)).setScale(3, RoundingMode.HALF_UP);
  }

  private static String fmt(double v) {
    return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).toPlainString();
  }

  private String lineName(Long id) {
    return lineRepo.findById(id).map(ProductLine::getName).orElse("#" + id);
  }

  private static String nz(String s) { return s == null ? "" : s; }

  private static String trim(String s) {
    if (s == null) return null;
    return s.length() > 1000 ? s.substring(0, 1000) : s;
  }
}
