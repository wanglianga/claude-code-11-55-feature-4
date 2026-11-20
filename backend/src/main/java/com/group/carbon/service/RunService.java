package com.group.carbon.service;

import com.group.carbon.common.BizException;
import com.group.carbon.common.EnergyTypes;
import com.group.carbon.common.Types.*;
import com.group.carbon.model.*;
import com.group.carbon.repo.*;
import com.group.carbon.service.allocation.AllocationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 核算运行编排：
 * 1. 启动时快照当前已批准因子清单（run_factor_bindings），运行期间因子被替换不影响本运行；
 * 2. 按 工厂地域 + 能源类型 + 适用期间 匹配批准因子，缺因子只把对应指标列为待核，不用集团平均数兜底；
 * 3. 结果与来源行、因子版本建立血缘。
 */
@Service
public class RunService {
  private final CalcRunRepo runRepo;
  private final RunFactorBindingRepo bindingRepo;
  private final EmissionResultRepo resultRepo;
  private final EmissionLineageRepo lineageRepo;
  private final SourceRowRepo rowRepo;
  private final SourceBatchRepo batchRepo;
  private final FactorRepo factorRepo;
  private final FactorVersionRepo versionRepo;
  private final PlantRepo plantRepo;
  private final AllocationService allocationService;

  public RunService(CalcRunRepo runRepo, RunFactorBindingRepo bindingRepo,
                    EmissionResultRepo resultRepo, EmissionLineageRepo lineageRepo,
                    SourceRowRepo rowRepo, SourceBatchRepo batchRepo,
                    FactorRepo factorRepo, FactorVersionRepo versionRepo, PlantRepo plantRepo,
                    AllocationService allocationService) {
    this.runRepo = runRepo;
    this.bindingRepo = bindingRepo;
    this.resultRepo = resultRepo;
    this.lineageRepo = lineageRepo;
    this.rowRepo = rowRepo;
    this.batchRepo = batchRepo;
    this.factorRepo = factorRepo;
    this.versionRepo = versionRepo;
    this.plantRepo = plantRepo;
    this.allocationService = allocationService;
  }

  public static void validatePeriod(String period) {
    if (period == null || !period.matches("\\d{4}-(0[1-9]|1[0-2])")) {
      throw BizException.badRequest("期间格式必须为 YYYY-MM，例如 2025-06");
    }
  }

  /** 创建正式运行：绑定启动时刻的批准因子清单。并发重复运行由数据库唯一索引拦截。 */
  @Transactional
  public CalcRun createOfficialRun(String period, String username) {
    validatePeriod(period);
    CalcRun run = new CalcRun();
    run.setRunNo(newNo("RUN"));
    run.setPeriod(period);
    run.setPurpose(RunPurpose.OFFICIAL);
    run.setStatus(RunStatus.RUNNING);
    run.setTriggeredBy(username);
    run.setStartedAt(LocalDateTime.now());
    runRepo.save(run);
    snapshotBindings(run);
    return run;
  }

  /** 候选运行（影响评估用）：同步创建并立即执行完成。 */
  @Transactional
  public CalcRun createCandidateRun(String period, String triggeredBy) {
    validatePeriod(period);
    CalcRun run = new CalcRun();
    run.setRunNo(newNo("CAND"));
    run.setPeriod(period);
    run.setPurpose(RunPurpose.CANDIDATE);
    run.setStatus(RunStatus.RUNNING);
    run.setTriggeredBy(triggeredBy);
    run.setStartedAt(LocalDateTime.now());
    runRepo.save(run);
    snapshotBindings(run);
    execute(run.getId());
    return runRepo.findById(run.getId()).orElseThrow();
  }

  private void snapshotBindings(CalcRun run) {
    LocalDate periodStart = LocalDate.parse(run.getPeriod() + "-01");
    List<FactorVersion> approved = versionRepo.findByStatus(FactorStatus.APPROVED);
    // 同一因子若存在多个覆盖该期间的批准版本，取版本号最大者
    Map<Long, FactorVersion> byFactor = new LinkedHashMap<>();
    for (FactorVersion v : approved) {
      boolean covers = !v.getValidFrom().isAfter(periodStart)
          && (v.getValidTo() == null || !v.getValidTo().isBefore(periodStart));
      if (!covers) continue;
      FactorVersion prev = byFactor.get(v.getFactorId());
      if (prev == null || v.getVersionNo() > prev.getVersionNo()) byFactor.put(v.getFactorId(), v);
    }
    for (FactorVersion v : byFactor.values()) {
      Factor f = factorRepo.findById(v.getFactorId()).orElseThrow();
      RunFactorBinding b = new RunFactorBinding();
      b.setRunId(run.getId());
      b.setFactorVersionId(v.getId());
      b.setFactorCode(f.getCode());
      b.setRegion(f.getRegion());
      b.setEnergyType(f.getEnergyType());
      b.setValue(v.getValue());
      b.setValidFrom(v.getValidFrom());
      b.setValidTo(v.getValidTo());
      bindingRepo.save(b);
    }
  }

  private record GroupKey(Long plantId, Long productLineId, String energyType) {}

  /** 执行核算：仅使用启动时绑定的因子清单；结果与来源行建立血缘。 */
  @Transactional
  public void execute(Long runId) {
    CalcRun run = runRepo.findById(runId).orElseThrow(() -> BizException.notFound("运行不存在"));
    if (run.getStatus() != RunStatus.RUNNING) return;
    try {
      List<RunFactorBinding> bindings = bindingRepo.findByRunId(runId);
      List<SourceRow> rows = rowRepo.findActiveVerifiedByPeriod(run.getPeriod(), RowStatus.ACTIVE);
      Map<Long, Plant> plants = plantRepo.findAll().stream()
          .collect(Collectors.toMap(Plant::getId, p -> p));

      Map<GroupKey, List<SourceRow>> groups = new LinkedHashMap<>();
      List<SourceRow> utilityRows = new ArrayList<>();
      for (SourceRow r : rows) {
        if (EnergyTypes.isSharedUtility(r.getEnergyType())) {
          utilityRows.add(r);
        } else {
          groups.computeIfAbsent(new GroupKey(r.getPlantId(), r.getProductLineId(), r.getEnergyType()),
              k -> new ArrayList<>()).add(r);
        }
      }

      for (Map.Entry<GroupKey, List<SourceRow>> e : groups.entrySet()) {
        GroupKey key = e.getKey();
        BigDecimal qty = e.getValue().stream()
            .map(SourceRow::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        Plant plant = plants.get(key.plantId());
        if (plant == null) throw BizException.badRequest("来源行引用了不存在的工厂: " + key.plantId());

        RunFactorBinding binding = findBinding(bindings, key.energyType(), plant.getRegion());
        EmissionResult res = buildResult(runId, key.plantId(), key.productLineId(), key.energyType(), qty, binding);
        resultRepo.save(res);
        for (SourceRow row : e.getValue()) {
          lineageRepo.save(new EmissionLineage(res.getId(), row.getId(), row.getQuantity()));
        }
      }

      // 共用资源（蒸汽/压缩空气）：以已固定的分配运行为准，未分配部分单列、区间随结果保留
      executeUtilityAllocation(runId, run.getPeriod(), bindings, utilityRows, plants);

      run.setStatus(RunStatus.COMPLETED);
      run.setCompletedAt(LocalDateTime.now());
      runRepo.save(run);
    } catch (Exception ex) {
      run.setStatus(RunStatus.FAILED);
      run.setError(ex.getMessage());
      run.setCompletedAt(LocalDateTime.now());
      runRepo.save(run);
    }
  }

  /** 共用资源核算：按 工厂×资源 取最近固定分配依据；无固定依据时总表量整体保留为未分配。 */
  private void executeUtilityAllocation(Long runId, String period, List<RunFactorBinding> bindings,
                                        List<SourceRow> utilityRows, Map<Long, Plant> plants) {
    Map<Long, Map<String, List<SourceRow>>> byPlantEnergy = new LinkedHashMap<>();
    for (SourceRow r : utilityRows) {
      byPlantEnergy.computeIfAbsent(r.getPlantId(), k -> new LinkedHashMap<>())
          .computeIfAbsent(r.getEnergyType(), k -> new ArrayList<>()).add(r);
    }
    for (Map.Entry<Long, Map<String, List<SourceRow>>> pe : byPlantEnergy.entrySet()) {
      Long plantId = pe.getKey();
      Plant plant = plants.get(plantId);
      if (plant == null) throw BizException.badRequest("来源行引用了不存在的工厂: " + plantId);
      for (Map.Entry<String, List<SourceRow>> ee : pe.getValue().entrySet()) {
        String energyType = ee.getKey();
        RunFactorBinding binding = findBinding(bindings, energyType, plant.getRegion());
        Optional<AllocationRun> fixed = allocationService.latestFixed(plantId, energyType, period);

        if (fixed.isPresent()) {
          AllocationRun alloc = fixed.get();
          for (AllocationRecord rec : allocationService.recordsOf(alloc.getId())) {
            if (rec.getQty().signum() == 0) continue;
            EmissionResult res = buildResult(runId, plantId, rec.getProductLineId(), energyType,
                rec.getQty(), binding);
            res.setAllocationRunId(alloc.getId());
            res.setQtyLow(rec.getQtyLow());
            res.setQtyHigh(rec.getQtyHigh());
            resultRepo.save(res);
            // 血缘锚定总表读数：各产品线分配量 + 未分配量之和 = 总表总量，可沿血缘回查守恒
            allocationService.mainRowId(alloc.getId()).ifPresent(mainRowId ->
                lineageRepo.save(new EmissionLineage(res.getId(), mainRowId, rec.getQty())));
          }
          if (alloc.getUnallocatedQty().signum() > 0) {
            EmissionResult un = buildResult(runId, plantId, null, energyType, alloc.getUnallocatedQty(), binding);
            un.setAllocationRunId(alloc.getId());
            resultRepo.save(un);
            allocationService.mainRowId(alloc.getId()).ifPresent(mainRowId ->
                lineageRepo.save(new EmissionLineage(un.getId(), mainRowId, alloc.getUnallocatedQty())));
          }
        } else {
          // 无固定分配依据：不按产量平均，总表读数整体作为未分配结果，报告勾稽将保持待核
          for (SourceRow mainRow : allocationService.mainRowsOf(plantId, energyType, period)) {
            EmissionResult un = buildResult(runId, plantId, null, energyType, mainRow.getQuantity(), binding);
            resultRepo.save(un);
            lineageRepo.save(new EmissionLineage(un.getId(), mainRow.getId(), mainRow.getQuantity()));
          }
        }
      }
    }
  }

  private RunFactorBinding findBinding(List<RunFactorBinding> bindings, String energyType, String region) {
    return bindings.stream()
        .filter(b -> b.getEnergyType().equals(energyType) && b.getRegion().equals(region))
        .findFirst().orElse(null);
  }

  private EmissionResult buildResult(Long runId, Long plantId, Long productLineId, String energyType,
                                     BigDecimal qty, RunFactorBinding binding) {
    EmissionResult res = new EmissionResult();
    res.setRunId(runId);
    res.setPlantId(plantId);
    res.setProductLineId(productLineId);
    res.setScope(EnergyTypes.scopeOf(energyType));
    res.setEnergyType(energyType);
    res.setActivityQty(qty);
    res.setUnit(EnergyTypes.unitOf(energyType));
    res.setQtyLow(qty);
    res.setQtyHigh(qty);
    if (binding != null) {
      res.setFactorVersionId(binding.getFactorVersionId());
      res.setFactorValue(binding.getValue());
      res.setEmissionTco2e(qty.multiply(binding.getValue())
          .divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP));
      res.setStatus(ResultStatus.OK);
    } else {
      // 缺少适用批准因子：指标列为待核，不用集团平均因子兜底
      res.setStatus(ResultStatus.PENDING_FACTOR);
    }
    return res;
  }

  // ---------------- 查询 ----------------

  public List<CalcRun> listRuns() { return runRepo.findAllByOrderByIdDesc(); }

  public CalcRun getRun(Long id) {
    return runRepo.findById(id).orElseThrow(() -> BizException.notFound("运行不存在: " + id));
  }

  public List<RunFactorBinding> bindingsOf(Long runId) { return bindingRepo.findByRunId(runId); }

  public List<EmissionResult> resultsOf(Long runId) { return resultRepo.findByRunIdOrderById(runId); }

  /** 血缘下钻：某条排放结果 ← 哪份来源行（表计/凭证）+ 哪版因子。 */
  public Map<String, Object> lineageOf(Long resultId) {
    EmissionResult res = resultRepo.findById(resultId)
        .orElseThrow(() -> BizException.notFound("排放结果不存在: " + resultId));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("result", res);

    Map<String, Object> factorInfo = null;
    if (res.getFactorVersionId() != null) {
      FactorVersion v = versionRepo.findById(res.getFactorVersionId()).orElse(null);
      if (v != null) {
        Factor f = factorRepo.findById(v.getFactorId()).orElse(null);
        factorInfo = new LinkedHashMap<>();
        factorInfo.put("factorCode", f != null ? f.getCode() : null);
        factorInfo.put("factorName", f != null ? f.getName() : null);
        factorInfo.put("region", f != null ? f.getRegion() : null);
        factorInfo.put("versionId", v.getId());
        factorInfo.put("versionNo", v.getVersionNo());
        factorInfo.put("value", v.getValue());
        factorInfo.put("validFrom", v.getValidFrom());
        factorInfo.put("validTo", v.getValidTo());
        factorInfo.put("status", v.getStatus());
        factorInfo.put("approvedBy", v.getApprovedBy());
        factorInfo.put("approvedAt", v.getApprovedAt());
      }
    }
    out.put("factorVersion", factorInfo);

    List<EmissionLineage> edges = lineageRepo.findByResultId(resultId);
    List<Map<String, Object>> sources = new ArrayList<>();
    for (EmissionLineage edge : edges) {
      SourceRow row = rowRepo.findById(edge.getSourceRowId()).orElse(null);
      if (row == null) continue;
      SourceBatch batch = batchRepo.findById(row.getBatchId()).orElse(null);
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("rowId", row.getId());
      s.put("rowNo", row.getRowNo());
      s.put("batchId", row.getBatchId());
      s.put("batchNo", batch != null ? batch.getBatchNo() : null);
      s.put("batchStatus", batch != null ? batch.getStatus() : null);
      s.put("verifiedBy", batch != null ? batch.getVerifiedBy() : null);
      s.put("verifiedAt", batch != null ? batch.getVerifiedAt() : null);
      s.put("uploadedBy", batch != null ? batch.getUploadedBy() : null);
      s.put("voucherNo", row.getVoucherNo());
      s.put("quantity", row.getQuantity());
      s.put("qtyUsed", edge.getQtyUsed());
      s.put("unit", row.getUnit());
      s.put("rowStatus", row.getStatus());
      sources.add(s);
    }
    out.put("sources", sources);

    // 共用资源结果：附带分配运行与分配证据快照（子表/系数/工时/平衡区间）
    if (res.getAllocationRunId() != null) {
      Map<String, Object> alloc = new LinkedHashMap<>();
      AllocationRun ar = allocationService.findRun(res.getAllocationRunId());
      alloc.put("allocationRun", ar);
      alloc.put("records", allocationService.recordsOf(res.getAllocationRunId()));
      alloc.put("evidence", allocationService.evidenceOf(res.getAllocationRunId()));
      out.put("allocation", alloc);
    }
    return out;
  }

  public static String newNo(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }
}
