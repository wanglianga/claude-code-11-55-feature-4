package com.group.carbon.model;

import com.group.carbon.common.Types.RowStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;

/** 工时台账：工厂 × 产品线 × 期间的实际工时，等级法最低等级证据。 */
@Entity
@Table(name = "work_hours")
public class WorkHour {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "plant_id", nullable = false)
  private Long plantId;
  @Column(name = "product_line_id", nullable = false)
  private Long productLineId;
  @Column(name = "energy_type", nullable = false)
  private String energyType;
  @Column(nullable = false)
  private String period;
  @Column(nullable = false)
  private BigDecimal hours;
  @Column(name = "source_ref")
  private String sourceRef;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private RowStatus status = RowStatus.ACTIVE;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getPlantId() { return plantId; }
  public void setPlantId(Long plantId) { this.plantId = plantId; }
  public Long getProductLineId() { return productLineId; }
  public void setProductLineId(Long productLineId) { this.productLineId = productLineId; }
  public String getEnergyType() { return energyType; }
  public void setEnergyType(String energyType) { this.energyType = energyType; }
  public String getPeriod() { return period; }
  public void setPeriod(String period) { this.period = period; }
  public BigDecimal getHours() { return hours; }
  public void setHours(BigDecimal hours) { this.hours = hours; }
  public String getSourceRef() { return sourceRef; }
  public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }
  public RowStatus getStatus() { return status; }
  public void setStatus(RowStatus status) { this.status = status; }
}
