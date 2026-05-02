package com.enterprise.aiassistant.repository;

import com.enterprise.aiassistant.entity.PromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, Long> {

    Optional<PromptTemplate> findByNameAndActiveTrue(String name);

    Optional<PromptTemplate> findByNameAndVersion(String name, int version);

    List<PromptTemplate> findByNameOrderByVersionDesc(String name);

    List<PromptTemplate> findByActiveTrue();

    @Modifying
    @Transactional
    @Query("UPDATE PromptTemplate p SET p.active = false WHERE p.name = :name")
    void deactivateAllByName(String name);
}
