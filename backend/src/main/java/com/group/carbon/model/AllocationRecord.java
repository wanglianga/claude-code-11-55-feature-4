package com.group.carbon.model;

import com.group.carbon.common.Types.EvidenceGrade;
import jakarta.persistence.*;
import java.math.BigDecimal;

/** 分配记录：每个产品线一条，点值 + 可行区间 + 证据等级。 */
@Entity
@Table(name = "allocation_records")
public class AllocationRecord {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "allocation_run_id", nullable = false)
  private Long allocationRunId;
  @Column(name = "product_line_id", nullable = false)
  private Long productLineId;
  /** 点值：等级法为确定值；平衡法为可行区间中点 */
  @Column(nullable = false)
  private BigDecimal qty;
  @Column(name = "qty_low", nullable = false)
  private BigDecimal qtyLow;
  @Column(name = "qty_high", nullable = false)
  private BigDecimal qtyHigh;
  @Enumerated(EnumType.STRING)
  @Column(name = "evidence_grade", nullable = false)
  private EvidenceGrade evidenceGrade;
  /** 不确定量（区间半宽；等级法为 0） */
  @Column(name = "uncertainty_qty", nullable = false)
  private BigDecimal uncertaintyQty = BigDecimal.ZERO;

  public AllocationRecord() {}
  public AllocationRecord(Long allocationRunId, Long productLineId, BigDecimal qty,
                          BigDecimal qtyLow, BigDecimal qtyHigh, EvidenceGrade grade,
                          BigDecimal uncertaintyQty) {
    this.allocationRunId = allocationRunId;
    this.productLineId = productLineId;
    this.qty = qty;
    this.qtyLow = qtyLow;
    this.qtyHigh = qtyHigh;
    this.evidenceGrade = grade;
    this.uncertaintyQty = uncertaintyQty;
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getAllocationRunId() { return allocationRunId; }
  public void setAllocationRunId(Long allocationRunId) { this.allocationRunId = allocationRunId; }
  public Long getProductLineId() { return productLineId; }
  public void setProductLineId(Long productLineId) { this.productLineId = productLineId; }
  public BigDecimal getQty() { return qty; }
  public void setQty(BigDecimal qty) { this.qty = qty; }
  public BigDecimal getQtyLow() { return qtyLow; }
  public void setQtyLow(BigDecimal qtyLow) { this.qtyLow = qtyLow; }
  public BigDecimal getQtyHigh() { return qtyHigh; }
  public void setQtyHigh(BigDecimal qtyHigh) { this.qtyHigh = qtyHigh; }
  public EvidenceGrade getEvidenceGrade() { return evidenceGrade; }
  public void setEvidenceGrade(EvidenceGrade evidenceGrade) { this.evidenceGrade = evidenceGrade; }
  public BigDecimal getUncertaintyQty() { return uncertaintyQty; }
  public void setUncertaintyQty(BigDecimal uncertaintyQty) { this.uncertaintyQty = uncertaintyQty; }
}
