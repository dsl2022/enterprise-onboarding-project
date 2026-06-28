package com.eop.request;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestEventRepository extends JpaRepository<RequestEventEntity, Long> {

    /** The timeline for a request, in append order (global bigserial id = total order). */
    List<RequestEventEntity> findByRequestIdOrderById(UUID requestId);
}
