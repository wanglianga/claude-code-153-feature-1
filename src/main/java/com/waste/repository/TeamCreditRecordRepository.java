package com.waste.repository;

import com.waste.entity.TeamCreditRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeamCreditRecordRepository extends JpaRepository<TeamCreditRecord, Long> {
    List<TeamCreditRecord> findByConstructionTeamOrderByIdDesc(String constructionTeam);
}
