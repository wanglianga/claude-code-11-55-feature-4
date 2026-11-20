package com.group.carbon.repo;

import com.group.carbon.common.Types.RowStatus;
import com.group.carbon.model.WorkHour;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkHourRepo extends JpaRepository<WorkHour, Long> {
  List<WorkHour> findByPlantIdAndPeriodOrderByIdAsc(Long plantId, String period);
  Optional<WorkHour> findByPlantIdAndProductLineIdAndPeriodAndEnergyTypeAndStatus(
      Long plantId, Long productLineId, String period, String energyType, RowStatus status);
}
