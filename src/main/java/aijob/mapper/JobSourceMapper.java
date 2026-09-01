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
}
