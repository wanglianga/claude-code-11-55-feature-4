package com.group.carbon.repo;

import com.group.carbon.model.AllocationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AllocationRecordRepo extends JpaRepository<AllocationRecord, Long> {
  List<AllocationRecord> findByAllocationRunIdOrderById(Long runId);
}
