package com.group.carbon.model;

import com.group.carbon.common.Types.AllocationMethod;
import com.group.carbon.common.Types.AllocationStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 分配运行：一次共用资源主口径声明。等级法与平衡法同为正式口径，不分主辅。 */
@Entity
@Table(name = "allocation_runs")
public class AllocationRun {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "run_no", nullable = false, unique = true)
  private String runNo;
  @Column(name = "plant_id", nullable = false)
  private Long plantId;
  @Column(name = "energy_type", nullable = false)
  private String energyType;
  @Column(nullable = false)
  private String period;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AllocationMethod method;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AllocationStatus status = AllocationStatus.DRAFT;
  /** 总表总量：守恒锚点 */
  @Column(name = "total_qty", nullable = false)
  private BigDecimal totalQty;
  @Column(name = "allocated_qty", nullable = false)
  private BigDecimal allocatedQty = BigDecimal.ZERO;
  @Column(name = "unallocated_qty", nullable = false)
  private BigDecimal unallocatedQty = BigDecimal.ZERO;
  /** 全部已分配量的可行区间（平衡法）；等级法与点值相同 */
  @Column(name = "uncertainty_low")
  private BigDecimal uncertaintyLow;
  @Column(name = "uncertainty_high")
  private BigDecimal uncertaintyHigh;
  /** 总表 -（已分配 + 未分配），固定时必须为 0 */
  @Column(name = "conservation_diff", nullable = false)
  private BigDecimal conservationDiff = BigDecimal.ZERO;
  private Boolean feasible;
  @Column(name = "solver_note", length = 2048)
  private String solverNote;
  @Column(name = "created_by", nullable = false)
  private String createdBy;
  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();
  @Column(name = "fixed_by")
  private String fixedBy;
  @Column(name = "fixed_at")
  private LocalDateTime fixedAt;
  @Column(name = "superseded_by_run_id")
  private Long supersededByRunId;
  @Column(name = "change_note", length = 1024)
  private String changeNote;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getRunNo() { return runNo; }
  public void setRunNo(String runNo) { this.runNo = runNo; }
  public Long getPlantId() { return plantId; }
  public void setPlantId(Long plantId) { this.plantId = plantId; }
  public String getEnergyType() { return energyType; }
  public void setEnergyType(String energyType) { this.energyType = energyType; }
  public String getPeriod() { return period; }
  public void setPeriod(String period) { this.period = period; }
  public AllocationMethod getMethod() { return method; }
  public void setMethod(AllocationMethod method) { this.method = method; }
  public AllocationStatus getStatus() { return status; }
  public void setStatus(AllocationStatus status) { this.status = status; }
  public BigDecimal getTotalQty() { return totalQty; }
  public void setTotalQty(BigDecimal totalQty) { this.totalQty = totalQty; }
  public BigDecimal getAllocatedQty() { return allocatedQty; }
  public void setAllocatedQty(BigDecimal allocatedQty) { this.allocatedQty = allocatedQty; }
  public BigDecimal getUnallocatedQty() { return unallocatedQty; }
  public void setUnallocatedQty(BigDecimal unallocatedQty) { this.unallocatedQty = unallocatedQty; }
  public BigDecimal getUncertaintyLow() { return uncertaintyLow; }
  public void setUncertaintyLow(BigDecimal uncertaintyLow) { this.uncertaintyLow = uncertaintyLow; }
  public BigDecimal getUncertaintyHigh() { return uncertaintyHigh; }
  public void setUncertaintyHigh(BigDecimal uncertaintyHigh) { this.uncertaintyHigh = uncertaintyHigh; }
  public BigDecimal getConservationDiff() { return conservationDiff; }
  public void setConservationDiff(BigDecimal conservationDiff) { this.conservationDiff = conservationDiff; }
  public Boolean getFeasible() { return feasible; }
  public void setFeasible(Boolean feasible) { this.feasible = feasible; }
  public String getSolverNote() { return solverNote; }
  public void setSolverNote(String solverNote) { this.solverNote = solverNote; }
  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public String getFixedBy() { return fixedBy; }
  public void setFixedBy(String fixedBy) { this.fixedBy = fixedBy; }
  public LocalDateTime getFixedAt() { return fixedAt; }
  public void setFixedAt(LocalDateTime fixedAt) { this.fixedAt = fixedAt; }
  public Long getSupersededByRunId() { return supersededByRunId; }
  public void setSupersededByRunId(Long supersededByRunId) { this.supersededByRunId = supersededByRunId; }
  public String getChangeNote() { return changeNote; }
  public void setChangeNote(String changeNote) { this.changeNote = changeNote; }
}
