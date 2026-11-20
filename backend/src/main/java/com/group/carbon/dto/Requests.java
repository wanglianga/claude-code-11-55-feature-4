package com.group.carbon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/** API 请求体定义。 */
public final class Requests {
  private Requests() {}

  public record LoginReq(@NotBlank(message = "用户名不能为空") String username,
                         @NotBlank(message = "密码不能为空") String password) {}

  /** plantId 为空时取批次工厂；集团采购批次可按行归属不同工厂；meterId 用于共用资源表计读数 */
  public record RowInput(Long plantId,
                         Long productLineId,
                         @NotBlank(message = "能源类型不能为空") String energyType,
                         @NotNull(message = "数量不能为空") BigDecimal quantity,
                         String voucherNo,
                         Long meterId) {}

  public record CreateBatchReq(@NotNull(message = "工厂不能为空") Long plantId,
                               @NotBlank(message = "批次类别不能为空") String category,
                               @NotBlank(message = "期间不能为空") String period,
                               String batchNo,
                               @NotEmpty(message = "批次至少包含一行数据") List<RowInput> rows) {}

  public record VoidReq(@NotBlank(message = "作废原因不能为空") String reason) {}

  public record CreateFactorReq(@NotBlank(message = "因子编码不能为空") String code,
                                @NotBlank(message = "因子名称不能为空") String name,
                                @NotBlank(message = "能源类型不能为空") String energyType,
                                @NotBlank(message = "地域不能为空") String region) {}

  public record CreateVersionReq(@NotNull(message = "因子值不能为空") BigDecimal value,
                                 @NotBlank(message = "适用期起不能为空") String validFrom,
                                 String validTo) {}

  public record CreateRunReq(@NotBlank(message = "期间不能为空") String period) {}

  public record CreateReportReq(@NotBlank(message = "报告标题不能为空") String title,
                                @NotBlank(message = "期间不能为空") String period,
                                @NotNull(message = "必须选择核算运行") Long runId) {}

  public record FreezeReq(@NotNull(message = "必须选择核算运行") Long runId, String note) {}

  public record ReasonReq(String reason) {}

  // ---------------- 共用资源分配 ----------------

  public record MeterReq(@NotBlank(message = "表计编号不能为空") String meterNo,
                         @NotNull(message = "工厂不能为空") Long plantId,
                         @NotBlank(message = "能源类型不能为空") String energyType,
                         @NotBlank(message = "表计角色不能为空") String role,
                         Long productLineId,
                         BigDecimal errorPct,
                         String calibrationRef,
                         String remark) {}

  public record CoefficientReq(@NotNull(message = "工厂不能为空") Long plantId,
                               @NotNull(message = "产品线不能为空") Long productLineId,
                               @NotBlank(message = "能源类型不能为空") String energyType,
                               @NotNull(message = "系数值不能为空") BigDecimal coefficient,
                               String basis,
                               @NotBlank(message = "适用起始期间不能为空") String validFrom,
                               String validTo) {}

  public record WorkHourReq(@NotNull(message = "工厂不能为空") Long plantId,
                            @NotNull(message = "产品线不能为空") Long productLineId,
                            @NotBlank(message = "能源类型不能为空") String energyType,
                            @NotBlank(message = "期间不能为空") String period,
                            @NotNull(message = "工时不能为空") BigDecimal hours,
                            String sourceRef) {}

  /** 创建分配运行：method=HIERARCHY 等级法 / BALANCE 平衡法；fix=true 直接固定为批次核算依据 */
  public record AllocationRunReq(@NotNull(message = "工厂不能为空") Long plantId,
                                 @NotBlank(message = "能源类型不能为空") String energyType,
                                 @NotBlank(message = "期间不能为空") String period,
                                 @NotBlank(message = "分配口径不能为空") String method,
                                 Boolean fix,
                                 String changeNote) {}

  public record AllocationFixReq(String changeNote) {}

  /** 工艺改造停用旧系数：validTo=适用截止期间，reason=停用原因（写入测算来源备注） */
  public record CoefficientRetireReq(String validTo, String reason) {}
}
