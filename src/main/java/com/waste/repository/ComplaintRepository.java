package com.waste.repository;

import com.waste.entity.Complaint;
import com.waste.enums.ComplaintStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {
    List<Complaint> findByComplainantIdOrderByIdDesc(Long complainantId);
    List<Complaint> findByStatusOrderByIdDesc(ComplaintStatus status);
    List<Complaint> findByTargetAppointmentId(Long appointmentId);
    List<Complaint> findByTargetRegistrationId(Long targetRegistrationId);
}
