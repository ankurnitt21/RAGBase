package com.enterprise.aiassistant.repository;

import com.enterprise.aiassistant.entity.QueryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QueryLogRepository extends JpaRepository<QueryLog, Long> {
}
