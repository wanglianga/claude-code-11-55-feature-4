package com.group.carbon.model;

import com.group.carbon.common.Types.MeterRole;
import com.group.carbon.common.Types.MeterStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 共用资源表计登记：总表（守恒锚点）或产品线局部子表。 */
@Entity
@Table(name = "utility_meters")
public class UtilityMeter {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "meter_no", nullable = false, unique = true)
  private String meterNo;
  @Column(name = "plant_id", nullable = false)
  private Long plantId;
  @Column(name = "energy_type", nullable = false)
  private String energyType;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private MeterRole role;
  @Column(name = "product_line_id")
  private Long productLineId;
  /** 校准误差界（百分数）：2.0 表示读数 ±2% */
  @Column(name = "error_pct", nullable = false)
  private BigDecimal errorPct = BigDecimal.ZERO;
  @Column(name = "calibration_ref")
  private String calibrationRef;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private MeterStatus status = MeterStatus.ACTIVE;
  private String remark;
  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getMeterNo() { return meterNo; }
  public void setMeterNo(String meterNo) { this.meterNo = meterNo; }
  public Long getPlantId() { return plantId; }
  public void setPlantId(Long plantId) { this.plantId = plantId; }
  public String getEnergyType() { return energyType; }
  public void setEnergyType(String energyType) { this.energyType = energyType; }
  public MeterRole getRole() { return role; }
  public void setRole(MeterRole role) { this.role = role; }
  public Long getProductLineId() { return productLineId; }
  public void setProductLineId(Long productLineId) { this.productLineId = productLineId; }
  public BigDecimal getErrorPct() { return errorPct; }
  public void setErrorPct(BigDecimal errorPct) { this.errorPct = errorPct; }
  public String getCalibrationRef() { return calibrationRef; }
  public void setCalibrationRef(String calibrationRef) { this.calibrationRef = calibrationRef; }
  public MeterStatus getStatus() { return status; }
  public void setStatus(MeterStatus status) { this.status = status; }
  public String getRemark() { return remark; }
  public void setRemark(String remark) { this.remark = remark; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
