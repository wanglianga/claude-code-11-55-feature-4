package com.group.carbon.repo;

import com.group.carbon.model.AllocationEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AllocationEvidenceRepo extends JpaRepository<AllocationEvidence, Long> {
  List<AllocationEvidence> findByAllocationRunIdOrderById(Long runId);
  List<AllocationEvidence> findByAllocationRecordIdOrderById(Long recordId);
}
