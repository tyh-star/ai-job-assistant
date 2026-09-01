package aijob.mapper;

import aijob.entity.JobSourceRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface JobSourceRecordMapper {

    String COLUMNS = """
            id, source_id, external_job_id, catalog_job_id, source_url, apply_url,
            payload_hash, raw_payload, first_seen_at, last_seen_at,
            last_successful_run_id, active, created_at, updated_at
            """;

    @Insert("""
            INSERT INTO job_source_records (
                source_id, external_job_id, catalog_job_id, source_url, apply_url,
                payload_hash, raw_payload, first_seen_at, last_seen_at,
                last_successful_run_id, active
            )
            VALUES (
                #{sourceId}, #{externalJobId}, #{catalogJobId}, #{sourceUrl}, #{applyUrl},
                #{payloadHash}, #{rawPayload}, #{firstSeenAt}, #{lastSeenAt},
                #{lastSuccessfulRunId}, #{active}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(JobSourceRecord record);

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM job_source_records
            WHERE source_id = #{sourceId}
              AND external_job_id = #{externalJobId}
            LIMIT 1
            """)
    JobSourceRecord findBySourceAndExternalId(@Param("sourceId") Long sourceId,
                                              @Param("externalJobId") String externalJobId);

    @Update("""
            UPDATE job_source_records
            SET catalog_job_id = #{catalogJobId},
                source_url = #{sourceUrl},
                apply_url = #{applyUrl},
                payload_hash = #{payloadHash},
                raw_payload = #{rawPayload},
                last_seen_at = #{lastSeenAt},
                last_successful_run_id = #{lastSuccessfulRunId},
                active = #{active}
            WHERE id = #{id}
            """)
    int update(JobSourceRecord record);

    /** Marks a record as still present without rewriting its payload. */
    @Update("""
            UPDATE job_source_records
            SET last_seen_at = #{lastSeenAt},
                last_successful_run_id = #{runId},
                active = TRUE
            WHERE id = #{id}
            """)
    int touch(@Param("id") Long id,
              @Param("lastSeenAt") LocalDateTime lastSeenAt,
              @Param("runId") Long runId);

    /**
     * Records that a successful run did not return. Used only after a run
     * completes successfully; a failed run must never expire anything.
     */
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM job_source_records
            WHERE source_id = #{sourceId}
              AND active = TRUE
              AND last_seen_at < #{cutoff}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<JobSourceRecord> findStale(@Param("sourceId") Long sourceId,
                                    @Param("cutoff") LocalDateTime cutoff,
                                    @Param("limit") int limit);

    @Update("""
            UPDATE job_source_records
            SET active = FALSE
            WHERE id = #{id}
            """)
    int deactivate(@Param("id") Long id);

    @Select("""
            SELECT COUNT(*)
            FROM job_source_records
            WHERE catalog_job_id = #{catalogJobId}
              AND active = TRUE
            """)
    long countActiveForCatalogJob(@Param("catalogJobId") Long catalogJobId);

    /**
     * Drops stored provider payloads for postings that went away long ago. Keeps the
     * record (and its source attribution) but stops retaining a full copy of a page
     * that is no longer published.
     */
    @Update("""
            UPDATE job_source_records
            SET raw_payload = NULL
            WHERE active = FALSE
              AND raw_payload IS NOT NULL
              AND last_seen_at < #{cutoff}
            """)
    int purgeRawPayloadsOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
