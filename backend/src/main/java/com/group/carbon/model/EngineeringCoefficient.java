package com.group.carbon.model;

import com.group.carbon.common.Types.MeterStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 工程系数：产品线单位工时的共用资源消耗量；工艺改造后以适用期封死旧系数。 */
@Entity
@Table(name = "engineering_coefficients")
public class EngineeringCoefficient {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "plant_id", nullable = false)
  private Long plantId;
  @Column(name = "product_line_id", nullable = false)
  private Long productLineId;
  @Column(name = "energy_type", nullable = false)
  private String energyType;
  @Column(nullable = false)
  private BigDecimal coefficient;
  private String basis;
  @Column(name = "valid_from", nullable = false)
  private String validFrom;
  @Column(name = "valid_to")
  private String validTo;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private MeterStatus status = MeterStatus.ACTIVE;
  @Column(name = "created_by", nullable = false)
  private String createdBy;
  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getPlantId() { return plantId; }
  public void setPlantId(Long plantId) { this.plantId = plantId; }
  public Long getProductLineId() { return productLineId; }
  public void setProductLineId(Long productLineId) { this.productLineId = productLineId; }
  public String getEnergyType() { return energyType; }
  public void setEnergyType(String energyType) { this.energyType = energyType; }
  public BigDecimal getCoefficient() { return coefficient; }
  public void setCoefficient(BigDecimal coefficient) { this.coefficient = coefficient; }
  public String getBasis() { return basis; }
  public void setBasis(String basis) { this.basis = basis; }
  public String getValidFrom() { return validFrom; }
  public void setValidFrom(String validFrom) { this.validFrom = validFrom; }
  public String getValidTo() { return validTo; }
  public void setValidTo(String validTo) { this.validTo = validTo; }
  public MeterStatus getStatus() { return status; }
  public void setStatus(MeterStatus status) { this.status = status; }
  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
