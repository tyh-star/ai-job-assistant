package aijob.mapper;

import aijob.entity.JobSource;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface JobSourceMapper {

    String COLUMNS = """
            id, provider_type, display_name, tenant_key, base_url, enabled, crawl_allowed,
            terms_url, collection_interval_minutes, last_success_at, next_run_at,
            consecutive_failures, created_at, updated_at
            """;

    @Insert("""
            INSERT INTO job_sources (
                provider_type, display_name, tenant_key, base_url, enabled, crawl_allowed,
                terms_url, collection_interval_minutes, next_run_at
            )
            VALUES (
                #{providerType}, #{displayName}, #{tenantKey}, #{baseUrl}, #{enabled}, #{crawlAllowed},
                #{termsUrl}, #{collectionIntervalMinutes}, #{nextRunAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(JobSource source);

    @Select("SELECT " + COLUMNS + " FROM job_sources WHERE id = #{id}")
    JobSource findById(@Param("id") Long id);

    @Select("SELECT " + COLUMNS + " FROM job_sources ORDER BY id")
    List<JobSource> findAll();

    /**
     * Looks a source up by the pair that identifies it. This is the same pair as
     * {@code uk_job_sources_provider_tenant}, so at most one row can match — and it is
     * the first half of a posting's identity: a posting is
     * {@code provider + board + external_id}, resolved as this row's id plus
     * {@code job_source_records.external_job_id}.
     */
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM job_sources
            WHERE provider_type = #{providerType}
              AND tenant_key = #{tenantKey}
            LIMIT 1
            """)
    JobSource findByProviderAndTenant(@Param("providerType") String providerType,
                                      @Param("tenantKey") String tenantKey);

    /**
     * Only sources the operator explicitly enabled AND marked crawl-allowed are ever
     * returned, so a misconfigured row can never start a collection.
     */
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM job_sources
            WHERE enabled = TRUE
              AND crawl_allowed = TRUE
              AND (next_run_at IS NULL OR next_run_at <= #{now})
            ORDER BY next_run_at IS NULL DESC, next_run_at
            LIMIT #{limit}
            """)
    List<JobSource> findDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE job_sources
            SET last_success_at = #{lastSuccessAt},
                next_run_at = #{nextRunAt},
                consecutive_failures = 0
            WHERE id = #{id}
            """)
    int markSuccess(@Param("id") Long id,
                    @Param("lastSuccessAt") LocalDateTime lastSuccessAt,
                    @Param("nextRunAt") LocalDateTime nextRunAt);

    @Update("""
            UPDATE job_sources
            SET consecutive_failures = consecutive_failures + 1,
                next_run_at = #{nextRunAt}
            WHERE id = #{id}
            """)
    int markFailure(@Param("id") Long id, @Param("nextRunAt") LocalDateTime nextRunAt);

    /**
     * Claims a source for one collector process. The scheduled collector and a manual
     * single-shot run are separate containers, so an in-process flag cannot keep them
     * off the same board; this conditional UPDATE is atomic in the database they share,
     * and exactly one caller gets the row.
     *
     * <p>A lease older than {@code staleBefore} is taken over, so a container that was
     * killed mid-run cannot block its boards forever.
     *
     * @return 1 when the lease was acquired, 0 when another process holds it.
     */
    @Update("""
            UPDATE job_sources
            SET locked_by = #{owner},
                locked_at = #{now}
            WHERE id = #{id}
              AND (locked_by IS NULL
                   OR locked_at IS NULL
                   OR locked_at < #{staleBefore}
                   OR locked_by = #{owner})
            """)
    int tryLock(@Param("id") Long id,
                @Param("owner") String owner,
                @Param("now") LocalDateTime now,
                @Param("staleBefore") LocalDateTime staleBefore);

    /**
     * Releases a lease this process still owns. Scoped to the owner so a process whose
     * lease already expired cannot free the claim of whoever took over from it.
     */
    @Update("""
            UPDATE job_sources
            SET locked_by = NULL,
                locked_at = NULL
            WHERE id = #{id}
              AND locked_by = #{owner}
            """)
    int releaseLock(@Param("id") Long id, @Param("owner") String owner);
}
