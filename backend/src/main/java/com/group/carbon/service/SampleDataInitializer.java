package com.group.carbon.service;

import com.group.carbon.common.Types.Role;
import com.group.carbon.dto.Requests.*;
import com.group.carbon.model.*;
import com.group.carbon.repo.*;
import com.group.carbon.security.AuthService;
import com.group.carbon.service.allocation.AllocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 样例数据初始化（仅当库为空时执行）：
 * 工厂/产品线/用户、因子目录（含未批准版本以演示待核指标）、
 * 2025-05 已核验批次 → 正式运行 → 已发布报告；
 * 2025-06 批次（含未分摊行、缺因子行、集团采购批次）用于验收回放。
 */
@Component
public class SampleDataInitializer implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(SampleDataInitializer.class);

  private final UserRepo userRepo;
  private final PlantRepo plantRepo;
  private final ProductLineRepo lineRepo;
  private final FactorRepo factorRepo;
  private final FactorVersionRepo versionRepo;
  private final BatchService batchService;
  private final RunService runService;
  private final FactorService factorService;
  private final DisclosureService disclosureService;
  private final AllocationService allocationService;

  public SampleDataInitializer(UserRepo userRepo, PlantRepo plantRepo, ProductLineRepo lineRepo,
                               FactorRepo factorRepo, FactorVersionRepo versionRepo,
                               BatchService batchService, RunService runService,
                               FactorService factorService, DisclosureService disclosureService,
                               AllocationService allocationService) {
    this.userRepo = userRepo;
    this.plantRepo = plantRepo;
    this.lineRepo = lineRepo;
    this.factorRepo = factorRepo;
    this.versionRepo = versionRepo;
    this.batchService = batchService;
    this.runService = runService;
    this.factorService = factorService;
    this.disclosureService = disclosureService;
    this.allocationService = allocationService;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (userRepo.count() > 0) {
      log.info("样例数据已存在，跳过初始化");
      return;
    }
    log.info("开始初始化样例数据...");

    // ---------- 用户 ----------
    user("admin", "admin123", "平台管理员", Role.ADMIN);
    user("analyst", "analyst123", "分析员-小苏", Role.ANALYST);
    user("auditor", "auditor123", "审核员-老沈", Role.AUDITOR);
    user("finance", "finance123", "财务披露-老周", Role.FINANCE);

    // ---------- 工厂与产品线 ----------
    Plant sz = plant("SZ", "苏州工厂", "CN-EAST");
    Plant cd = plant("CD", "成都工厂", "CN-WEST");
    ProductLine l1 = line("L1", "精密结构件");
    ProductLine l2 = line("L2", "电池模组");

    // ---------- 因子目录 ----------
    Factor elecEast = factor("ELEC-CNEAST", "华东电网电力排放因子", "GRID_ELEC", "CN-EAST");
    approveVersion(elecEast, "0.5566", "2025-01-01", "2025-12-31");
    Factor elecWest = factor("ELEC-CNWEST", "西南电网电力排放因子", "GRID_ELEC", "CN-WEST");
    approveVersion(elecWest, "0.6100", "2025-01-01", "2025-12-31");
    Factor ngEast = factor("NG-CNEAST", "华东天然气排放因子", "NATURAL_GAS", "CN-EAST");
    approveVersion(ngEast, "2.1500", "2025-01-01", "2025-12-31");
    // 西南天然气因子仅有草稿：成都天然气指标将列为待核
    Factor ngWest = factor("NG-CNWEST", "西南天然气排放因子", "NATURAL_GAS", "CN-WEST");
    draftVersion(ngWest, "2.0800", "2025-01-01", "2025-12-31");
    Factor dieselEast = factor("DIESEL-CNEAST", "华东柴油排放因子", "DIESEL", "CN-EAST");
    approveVersion(dieselEast, "2.6800", "2025-01-01", "2025-12-31");
    Factor steelEast = factor("STEEL-CNEAST", "华东采购钢材排放因子", "PURCH_STEEL", "CN-EAST");
    approveVersion(steelEast, "2100.0", "2025-01-01", "2025-12-31");
    Factor steelWest = factor("STEEL-CNWEST", "西南采购钢材排放因子", "PURCH_STEEL", "CN-WEST");
    approveVersion(steelWest, "1950.0", "2025-01-01", "2025-12-31");

    // ---------- 2025-05 来源批次（全部已核验，因子齐备） ----------
    verify(batch("B-2025-05-ELEC-SZ", sz, "ELECTRICITY", "2025-05", List.of(
        row(l1, "GRID_ELEC", "680000", null),
        row(l2, "GRID_ELEC", "420000", null))));
    verify(batch("B-2025-05-FUEL-SZ", sz, "FUEL", "2025-05", List.of(
        row(l1, "NATURAL_GAS", "28000", null),
        row(l1, "DIESEL", "4000", null))));
    verify(batch("B-2025-05-ELEC-CD", cd, "ELECTRICITY", "2025-05", List.of(
        row(l1, "GRID_ELEC", "350000", null),
        row(l2, "GRID_ELEC", "150000", null))));
    verify(batch("B-2025-05-PROC-GP", sz, "PROCUREMENT", "2025-05", List.of(
        row(sz, l1, "PURCH_STEEL", "480", "V-9001"),
        row(cd, l1, "PURCH_STEEL", "260", "V-9002"))));

    // ---------- 2025-05 正式运行 → 冻结 → 审批 → 发布 ----------
    CalcRun mayRun = runService.createOfficialRun("2025-05", "analyst");
    runService.execute(mayRun.getId());
    Report mayReport = disclosureService.createReport("analyst",
        "2025年5月集团碳排放披露报告", "2025-05", mayRun.getId());
    disclosureService.approve(mayReport.getId(), "finance");
    disclosureService.publish(mayReport.getId(), "finance");
    log.info("2025-05 报告已发布: {}", mayReport.getCode());

    // ---------- 2025-06 来源批次（用于验收回放） ----------
    // 苏州电力：含一行未分摊到产品线的厂区公共用电 → 勾稽差异演示
    verify(batch("B-2025-06-ELEC-SZ", sz, "ELECTRICITY", "2025-06", List.of(
        row(l1, "GRID_ELEC", "700000", null),
        row(l2, "GRID_ELEC", "500000", null),
        row(null, "GRID_ELEC", "100000", null))));
    verify(batch("B-2025-06-FUEL-SZ", sz, "FUEL", "2025-06", List.of(
        row(l1, "NATURAL_GAS", "30000", null),
        row(l2, "NATURAL_GAS", "18000", null))));
    verify(batch("B-2025-06-ELEC-CD", cd, "ELECTRICITY", "2025-06", List.of(
        row(l1, "GRID_ELEC", "400000", null))));
    // 成都天然气：西南天然气因子未批准 → 待核指标演示
    verify(batch("B-2025-06-FUEL-CD", cd, "FUEL", "2025-06", List.of(
        row(l2, "NATURAL_GAS", "12000", null))));
    // 集团采购批次：两家工厂共享，用于并发修订冲突演示
    verify(batch("B-2025-06-PROC-GP", sz, "PROCUREMENT", "2025-06", List.of(
        row(sz, l1, "PURCH_STEEL", "500", "V-1001"),
        row(cd, l1, "PURCH_STEEL", "300", "V-1002"))));

    // ---------- 共用资源（蒸汽/压缩空气）分配证据链 ----------
    seedSharedUtilities(sz, cd, l1, l2);

    log.info("样例数据初始化完成");
  }

  /** 共用资源：表计/系数/工时 → 等级法与平衡法分配 → 工艺改造批次依据固定。 */
  private void seedSharedUtilities(Plant sz, Plant cd, ProductLine l1, ProductLine l2) {
    // 因子：蒸汽、压缩空气（华东，已批准）
    Factor steamEast = factor("STEAM-CNEAST", "华东蒸汽排放因子", "STEAM", "CN-EAST");
    approveVersion(steamEast, "110.0", "2025-01-01", "2025-12-31");
    Factor caEast = factor("COMPAIR-CNEAST", "华东压缩空气排放因子", "COMP_AIR", "CN-EAST");
    approveVersion(caEast, "0.08", "2025-01-01", "2025-12-31");

    // 表计：苏州蒸汽总表+L1子表；压缩空气总表+L1子表（L2 无子表、无工时 → 未分配演示）
    UtilityMeter stMain = meter("M-SZ-ST-01", sz, "STEAM", "MAIN", null, "1.0", "CAL-2025-001");
    UtilityMeter stL1 = meter("M-SZ-ST-L1", sz, "STEAM", "SUB", l1, "2.0", "CAL-2025-002");
    UtilityMeter caMain = meter("M-SZ-CA-01", sz, "COMP_AIR", "MAIN", null, "1.0", "CAL-2025-003");
    UtilityMeter caL1 = meter("M-SZ-CA-L1", sz, "COMP_AIR", "SUB", l1, "2.0", "CAL-2025-004");

    // 工程系数（单位工时消耗量）
    coef(sz, l1, "STEAM", "0.85", "2025-01", null, "锅炉分汽缸台账测算");
    coef(sz, l2, "STEAM", "2.05", "2025-01", null, "工艺热平衡工程测算");
    coef(sz, l1, "COMP_AIR", "7.0", "2025-01", null, "空压站流量计对照测算");
    coef(sz, l2, "COMP_AIR", "12.0", "2025-01", null, "空压站额定参数推算");

    // 工时台账（2025-07）：蒸汽两条线齐全；压缩空气仅 L1 有工时（L2 缺工时 → 未分配演示）
    workHour(sz, l1, "STEAM", "2025-07", "500", "WH-2507-01");
    workHour(sz, l2, "STEAM", "2025-07", "300", "WH-2507-02");
    workHour(sz, l1, "COMP_AIR", "2025-07", "400", "WH-2507-03");

    // 2025-07 读数批次（总表 + 子表），核验后进入分配证据链
    verify(utilBatch("B-2025-07-STEAM-SZ", sz, "2025-07", List.of(
        utilRow(stMain, null, "1000"),
        utilRow(stL1, l1, "400"))));
    verify(utilBatch("B-2025-07-CA-SZ", sz, "2025-07", List.of(
        utilRow(caMain, null, "8000"),
        utilRow(caL1, l1, "3000"))));

    // 等级法固定依据：蒸汽 L1 子表 400 + L2 系数推算（615>余量600，等比缩放为600），全量分配守恒
    allocationService.createRun("analyst", new AllocationRunReq(
        sz.getId(), "STEAM", "2025-07", "HIERARCHY", true, null));
    // 平衡法同口径演示（不固定，仅展示可行区间）
    allocationService.createRun("analyst", new AllocationRunReq(
        sz.getId(), "STEAM", "2025-07", "BALANCE", false, null));
    // 压缩空气：L1 子表 3000，L2 缺子表且缺工时 → 5000 保留未分配
    allocationService.createRun("analyst", new AllocationRunReq(
        sz.getId(), "COMP_AIR", "2025-07", "HIERARCHY", true, null));

    // 成都压缩空气：L1 子表计量；L2 无适用系数但有工时 → 第三级按工时占比分摊（WORK_HOUR 证据）
    Factor caWest = factor("COMPAIR-CNWEST", "西南压缩空气排放因子", "COMP_AIR", "CN-WEST");
    approveVersion(caWest, "0.09", "2025-01-01", "2025-12-31");
    UtilityMeter cdCaMain = meter("M-CD-CA-01", cd, "COMP_AIR", "MAIN", null, "1.0", "CAL-2025-201");
    UtilityMeter cdCaL1 = meter("M-CD-CA-L1", cd, "COMP_AIR", "SUB", l1, "2.0", "CAL-2025-202");
    coef(cd, l1, "COMP_AIR", "6.0", "2025-01", null, "成都空压站流量对照测算");
    workHour(cd, l1, "COMP_AIR", "2025-07", "350", "WH-2507-05");
    workHour(cd, l2, "COMP_AIR", "2025-07", "250", "WH-2507-06");
    verify(utilBatch("B-2025-07-CA-CD", cd, "2025-07", List.of(
        utilRow(cdCaMain, null, "6000"),
        utilRow(cdCaL1, l1, "2500"))));
    allocationService.createRun("analyst", new AllocationRunReq(
        cd.getId(), "COMP_AIR", "2025-07", "HIERARCHY", true,
        "L2 无校准系数，按工时占比分摊总表余量（第三级证据）"));

    // 2025-07 正式核算运行（使用上述固定分配依据）
    CalcRun julRun = runService.createOfficialRun("2025-07", "analyst");
    runService.execute(julRun.getId());

    // ---------- 2025-08 工艺改造：旧表停用、旧系数封死适用期，新表/新系数另起 ----------
    allocationService.retireMeter(stMain.getId(), "锅炉改造，旧总表停用");
    allocationService.retireMeter(stL1.getId(), "蒸汽管路改造，L1 旧子表停用");
    UtilityMeter stMain2 = meter("M-SZ-ST-02", sz, "STEAM", "MAIN", null, "1.0", "CAL-2025-101");
    UtilityMeter stL1b = meter("M-SZ-ST-L1B", sz, "STEAM", "SUB", l1, "1.5", "CAL-2025-102");
    UtilityMeter stL2b = meter("M-SZ-ST-L2B", sz, "STEAM", "SUB", l2, "1.5", "CAL-2025-103");

    List<EngineeringCoefficient> oldSteamCoefs =
        allocationService.listCoefficients(sz.getId(), "STEAM");
    for (EngineeringCoefficient c : oldSteamCoefs) {
      allocationService.retireCoefficient(c.getId(), "2025-07");
    }
    coef(sz, l1, "STEAM", "0.80", "2025-08", null, "改造后锅炉热平衡复测");
    coef(sz, l2, "STEAM", "1.95", "2025-08", null, "改造后工艺复测");
    workHour(sz, l1, "STEAM", "2025-08", "520", "WH-2508-01");
    workHour(sz, l2, "STEAM", "2025-08", "310", "WH-2508-02");

    verify(utilBatch("B-2025-08-STEAM-SZ", sz, "2025-08", List.of(
        utilRow(stMain2, null, "950"),
        utilRow(stL1b, l1, "400"),
        utilRow(stL2b, l2, "280"))));
    allocationService.createRun("analyst", new AllocationRunReq(
        sz.getId(), "STEAM", "2025-08", "HIERARCHY", true,
        "工艺改造后首批次：固定改造后表计与系数依据，改造前 2025-07 批次依据保持不变"));
    CalcRun augRun = runService.createOfficialRun("2025-08", "analyst");
    runService.execute(augRun.getId());
  }

  // ---------- 共用资源辅助 ----------

  private UtilityMeter meter(String no, Plant plant, String energyType, String role,
                             ProductLine line, String errorPct, String calib) {
    return allocationService.createMeter("analyst", new MeterReq(
        no, plant.getId(), energyType, role, line != null ? line.getId() : null,
        new BigDecimal(errorPct), calib, null));
  }

  private EngineeringCoefficient coef(Plant plant, ProductLine line, String energyType,
                                      String value, String from, String to, String basis) {
    return allocationService.createCoefficient("analyst", new CoefficientReq(
        plant.getId(), line.getId(), energyType, new BigDecimal(value), basis, from, to));
  }

  private void workHour(Plant plant, ProductLine line, String energyType,
                        String period, String hours, String ref) {
    allocationService.createWorkHour(new WorkHourReq(
        plant.getId(), line.getId(), energyType, period, new BigDecimal(hours), ref));
  }

  private RowInput utilRow(UtilityMeter meter, ProductLine line, String qty) {
    return new RowInput(null, line != null ? line.getId() : null, meter.getEnergyType(),
        new BigDecimal(qty), null, meter.getId());
  }

  private SourceBatch utilBatch(String batchNo, Plant plant, String period, List<RowInput> rows) {
    return batchService.create("analyst",
        new CreateBatchReq(plant.getId(), "UTILITY", period, batchNo, rows));
  }

  // ---------- 辅助 ----------

  private void user(String username, String password, String displayName, Role role) {
    User u = new User();
    u.setUsername(username);
    u.setPasswordHash(AuthService.hashPassword(username, password));
    u.setDisplayName(displayName);
    u.setRole(role);
    userRepo.save(u);
  }

  private Plant plant(String code, String name, String region) {
    Plant p = new Plant();
    p.setCode(code);
    p.setName(name);
    p.setRegion(region);
    return plantRepo.save(p);
  }

  private ProductLine line(String code, String name) {
    ProductLine l = new ProductLine();
    l.setCode(code);
    l.setName(name);
    return lineRepo.save(l);
  }

  private Factor factor(String code, String name, String energyType, String region) {
    return factorService.createFactor("admin", new CreateFactorReq(code, name, energyType, region));
  }

  private void approveVersion(Factor f, String value, String from, String to) {
    FactorVersion v = factorService.createVersion(f.getId(), "analyst",
        new CreateVersionReq(new BigDecimal(value), from, to));
    factorService.approve(v.getId(), "auditor");
  }

  private void draftVersion(Factor f, String value, String from, String to) {
    factorService.createVersion(f.getId(), "analyst",
        new CreateVersionReq(new BigDecimal(value), from, to));
  }

  private RowInput row(ProductLine line, String energyType, String quantity, String voucher) {
    return new RowInput(null, line != null ? line.getId() : null, energyType,
        new BigDecimal(quantity), voucher, null);
  }

  private RowInput row(Plant plant, ProductLine line, String energyType, String quantity, String voucher) {
    return new RowInput(plant.getId(), line != null ? line.getId() : null, energyType,
        new BigDecimal(quantity), voucher, null);
  }

  private SourceBatch batch(String batchNo, Plant plant, String category, String period, List<RowInput> rows) {
    return batchService.create("analyst",
        new CreateBatchReq(plant.getId(), category, period, batchNo, rows));
  }

  private void verify(SourceBatch batch) {
    batchService.verify(batch.getId(), "auditor");
  }
}
