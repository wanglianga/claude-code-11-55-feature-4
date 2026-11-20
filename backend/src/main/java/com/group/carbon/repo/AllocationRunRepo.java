package com.group.carbon.repo;

import com.group.carbon.common.Types.AllocationStatus;
import com.group.carbon.model.AllocationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface AllocationRunRepo extends JpaRepository<AllocationRun, Long> {
  List<AllocationRun> findAllByOrderByIdDesc();
  List<AllocationRun> findByPlantIdAndEnergyTypeAndPeriodOrderByIdDesc(
      Long plantId, String energyType, String period);
  List<AllocationRun> findByPlantIdAndPeriodOrderByIdDesc(Long plantId, String period);
  Optional<AllocationRun> findFirstByPlantIdAndEnergyTypeAndPeriodAndStatusOrderByIdDesc(
      Long plantId, String energyType, String period, AllocationStatus status);

  /** 核算运行取数：某工厂/资源/期间最近一次已固定的分配运行。 */
  default Optional<AllocationRun> findLatestFixed(Long plantId, String energyType, String period) {
    return findByPlantIdAndEnergyTypeAndPeriodOrderByIdDesc(plantId, energyType, period).stream()
        .filter(r -> r.getStatus() == AllocationStatus.FIXED).findFirst();
  }

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM AllocationRun a WHERE a.id = :id")
  Optional<AllocationRun> findByIdForUpdate(@Param("id") Long id);
}
