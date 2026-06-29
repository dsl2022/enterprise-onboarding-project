package com.eop.access;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogRepository extends JpaRepository<CatalogEntity, String> {

    /** Catalog listing with optional type/risk filters. */
    @Query("""
            SELECT c FROM CatalogEntity c
             WHERE (:type IS NULL OR c.type = :type)
               AND (:risk IS NULL OR c.risk = :risk)
            """)
    Page<CatalogEntity> search(@Param("type") String type, @Param("risk") String risk, Pageable pageable);
}
