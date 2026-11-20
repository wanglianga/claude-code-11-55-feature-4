package com.group.carbon.repo;

import com.group.carbon.model.EngineeringCoefficient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EngineeringCoefficientRepo extends JpaRepository<EngineeringCoefficient, Long> {
  List<EngineeringCoefficient> findByPlantIdAndProductLineIdAndEnergyTypeOrderByValidFromDesc(
      Long plantId, Long productLineId, String energyType);
  List<EngineeringCoefficient> findByPlantIdAndEnergyTypeOrderByIdAsc(Long plantId, String energyType);
}
