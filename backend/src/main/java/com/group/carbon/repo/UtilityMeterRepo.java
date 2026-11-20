package com.group.carbon.repo;

import com.group.carbon.common.Types.MeterRole;
import com.group.carbon.common.Types.MeterStatus;
import com.group.carbon.model.UtilityMeter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UtilityMeterRepo extends JpaRepository<UtilityMeter, Long> {
  List<UtilityMeter> findByPlantIdAndEnergyTypeOrderByRoleAscIdAsc(Long plantId, String energyType);
  Optional<UtilityMeter> findFirstByPlantIdAndEnergyTypeAndRoleAndStatus(
      Long plantId, String energyType, MeterRole role, MeterStatus status);
  boolean existsByMeterNo(String meterNo);
}
