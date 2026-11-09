package dev.trakr.api.search.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Writes go through JPA; the filter picker's list is a jOOQ join (SearchQueries.filters). */
public interface SavedFilterRepository extends JpaRepository<SavedFilter, UUID> {
}
