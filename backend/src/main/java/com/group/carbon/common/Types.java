package com.group.carbon.common;

/** 平台全部业务枚举，集中定义避免散落。 */
public final class Types {
  private Types() {}

  public enum Role { ADMIN, ANALYST, AUDITOR, FINANCE }

  /** 批次状态：已上传 / 已核验 / 已驳回 / 冲突 / 被取代 / 已作废 */
  public enum BatchStatus { UPLOADED, VERIFIED, REJECTED, CONFLICT, SUPERSEDED, VOID }

  public enum RowStatus { ACTIVE, SUPERSEDED, VOID }

  /** 因子版本状态：草稿 / 已批准 / 被替换 / 已停用 */
  public enum FactorStatus { DRAFT, APPROVED, REPLACED, RETIRED }

  public enum RunStatus { RUNNING, COMPLETED, FAILED }

  /** OFFICIAL=正式运行；CANDIDATE=影响评估产生的候选运行 */
  public enum RunPurpose { OFFICIAL, CANDIDATE }

  /** OK=正常；PENDING_FACTOR=缺少适用批准因子，指标待核 */
  public enum ResultStatus { OK, PENDING_FACTOR }

  /** RECONCILE_FAILED 即“待核”状态：勾稽不平或存在待核指标 */
  public enum ReportStatus { DRAFT, PENDING_REVIEW, RECONCILE_FAILED, APPROVED, PUBLISHED }

  public enum VersionStatus { FROZEN, PUBLISHED, SUPERSEDED, RESTATED }

  public enum ReconcileStatus { OK, MISMATCH, PENDING_FACTOR }

  public enum TriggerType { SOURCE_SUPPLEMENT, SOURCE_REVISION, SOURCE_MISSING, FACTOR_REVISION }

  public enum ImpactStatus { OPEN, ACKNOWLEDGED, RESOLVED }

  public enum RestatementStatus { PROPOSED, ACCEPTED, REJECTED, RESOLVED }

  /** 分配主口径：HIERARCHY=证据等级依次使用子表/校准系数/工时；BALANCE=总表+误差界+产能平衡求解。两者均为正式口径 */
  public enum AllocationMethod { HIERARCHY, BALANCE }

  /** 分配运行状态：草稿 / 已固定（批次核算依据，不可改写）/ 被取代（后补表计产生新版本） */
  public enum AllocationStatus { DRAFT, FIXED, SUPERSEDED }

  /** 表计角色：MAIN=总表（共用资源总量）；SUB=产品线局部子表 */
  public enum MeterRole { MAIN, SUB }

  /** 表计状态：在用 / 停用（工艺改造后旧表停用，历史读数保留） */
  public enum MeterStatus { ACTIVE, RETIRED }

  /** 分配证据等级：SUB_METER 子表 / COEF 校准系数 / WORK_HOUR 工时 / BALANCE 平衡求解 / UNALLOCATED 未分配 */
  public enum EvidenceGrade { SUB_METER, COEF, WORK_HOUR, BALANCE, UNALLOCATED }
}
