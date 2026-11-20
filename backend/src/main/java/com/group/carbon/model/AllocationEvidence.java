package com.group.carbon.model;

import com.group.carbon.common.Types.EvidenceGrade;
import jakarta.persistence.*;
import java.math.BigDecimal;

/** 分配证据快照：分配记录/未分配部分 ← 具体表计读数、工程系数或工时，支持回查复算。 */
@Entity
@Table(name = "allocation_evidence")
public class AllocationEvidence {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "allocation_run_id", nullable = false)
  private Long allocationRunId;
  /** 为空表示未分配部分的证据 */
  @Column(name = "allocation_record_id")
  private Long allocationRecordId;
  @Enumerated(EnumType.STRING)
  @Column(name = "evidence_grade", nullable = false)
  private EvidenceGrade evidenceGrade;
  @Column(name = "meter_id")
  private Long meterId;
  @Column(name = "source_row_id")
  private Long sourceRowId;
  @Column(name = "coefficient_id")
  private Long coefficientId;
  @Column(name = "work_hour_id")
  private Long workHourId;
  @Column(name = "ref_label", nullable = false)
  private String refLabel;
  /** 原始读数或推算消耗量 */
  @Column(name = "reading_qty")
  private BigDecimal readingQty;
  /** 实际计入分配的量 */
  @Column(name = "applied_qty", nullable = false)
  private BigDecimal appliedQty;
  @Column(length = 1024)
  private String detail;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getAllocationRunId() { return allocationRunId; }
  public void setAllocationRunId(Long allocationRunId) { this.allocationRunId = allocationRunId; }
  public Long getAllocationRecordId() { return allocationRecordId; }
  public void setAllocationRecordId(Long allocationRecordId) { this.allocationRecordId = allocationRecordId; }
  public EvidenceGrade getEvidenceGrade() { return evidenceGrade; }
  public void setEvidenceGrade(EvidenceGrade evidenceGrade) { this.evidenceGrade = evidenceGrade; }
  public Long getMeterId() { return meterId; }
  public void setMeterId(Long meterId) { this.meterId = meterId; }
  public Long getSourceRowId() { return sourceRowId; }
  public void setSourceRowId(Long sourceRowId) { this.sourceRowId = sourceRowId; }
  public Long getCoefficientId() { return coefficientId; }
  public void setCoefficientId(Long coefficientId) { this.coefficientId = coefficientId; }
  public Long getWorkHourId() { return workHourId; }
  public void setWorkHourId(Long workHourId) { this.workHourId = workHourId; }
  public String getRefLabel() { return refLabel; }
  public void setRefLabel(String refLabel) { this.refLabel = refLabel; }
  public BigDecimal getReadingQty() { return readingQty; }
  public void setReadingQty(BigDecimal readingQty) { this.readingQty = readingQty; }
  public BigDecimal getAppliedQty() { return appliedQty; }
  public void setAppliedQty(BigDecimal appliedQty) { this.appliedQty = appliedQty; }
  public String getDetail() { return detail; }
  public void setDetail(String detail) { this.detail = detail; }
}
