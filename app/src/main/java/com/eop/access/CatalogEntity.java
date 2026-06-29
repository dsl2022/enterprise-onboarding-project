package com.eop.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A grantable catalog resource (access-owned reference data, seeded in V6). Read-only in v1. */
@Entity
@Table(schema = "access", name = "catalog")
public class CatalogEntity {

    @Id
    private String id;

    private String name;
    private String type;   // AWS | WORKDAY | ROLE | TEAM
    private String risk;   // LOW | MEDIUM | HIGH
    private String description;

    @Column(name = "mapped_group")
    private String mappedGroup;

    protected CatalogEntity() {
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getRisk() {
        return risk;
    }

    public String getDescription() {
        return description;
    }

    public String getMappedGroup() {
        return mappedGroup;
    }
}
