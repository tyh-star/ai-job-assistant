package aijob.mapper;

import aijob.entity.Job;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface JobMapper {

    @Insert("""
            INSERT INTO jobs (
                user_id, company, title, location, job_url,
                description, source, status, external_job_id
            )
            VALUES (
                #{userId}, #{company}, #{title}, #{location}, #{jobUrl},
                #{description}, #{source}, #{status}, #{externalJobId}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Job job);

    @Select("SELECT * FROM jobs WHERE id = #{id}")
    Job findById(Long id);

    @Select("""
            SELECT * FROM jobs
            WHERE user_id = #{userId}
            ORDER BY created_at DESC
            """)
    List<Job> findByUserId(Long userId);

    @Select("""
            SELECT * FROM jobs
            WHERE user_id = #{userId}
              AND source = #{source}
              AND external_job_id = #{externalJobId}
            LIMIT 1
            """)
    Job findByUserIdAndSourceAndExternalJobId(
            @Param("userId") Long userId,
            @Param("source") String source,
            @Param("externalJobId") String externalJobId);

    /**
     * Which of {@code externalJobIds} this user has already saved under {@code source}.
     * One query per search page instead of one per row, for a provider (FreeHire) whose
     * catalog is not mirrored locally and so has no {@code user_saved_catalog_jobs}-style
     * mapping table to join against.
     */
    // Column aliases are the record's own component names, so MyBatis's automatic
    // constructor mapping binds them without a hand-written @ResultMap.
    @Select("""
            <script>
            SELECT external_job_id AS externalJobId, id AS id,
                   applied AS applied, applied_at AS appliedAt
            FROM jobs
            WHERE user_id = #{userId}
              AND source = #{source}
              AND external_job_id IN
              <foreach item="externalId" collection="externalJobIds" open="(" separator="," close=")">
                  #{externalId}
              </foreach>
            </script>
            """)
    List<ExternalIdAndPersonalJobId> findSavedExternalJobIds(
            @Param("userId") Long userId,
            @Param("source") String source,
            @Param("externalJobIds") List<String> externalJobIds);

    /**
     * One row of {@link #findSavedExternalJobIds}: an external id, the personal job it
     * became, and whether the user has confirmed they applied to it.
     */
    record ExternalIdAndPersonalJobId(String externalJobId, Long id, boolean applied, LocalDateTime appliedAt) {
    }

    /** Batch fetch, for building catalog summaries without one query per row. */
    @Select("""
            <script>
            SELECT * FROM jobs
            WHERE id IN
            <foreach item="jobId" collection="ids" open="(" separator="," close=")">
                #{jobId}
            </foreach>
            </script>
            """)
    List<Job> findByIds(@Param("ids") List<Long> ids);

    /**
     * Confirms the user applied. Idempotent in the strict sense both callers of this
     * method need (the Jobs page's own tabs AND the job-catalog search-card flow --
     * see {@code JobService#markApplied} and {@code JobCatalogService#markApplied}):
     * a repeat confirmation must never reset {@code applied_at} once it is set, only
     * the genuine {@code false -> true} transition (including one after a revoke) may
     * set a new one.
     *
     * <p>{@code COALESCE(applied_at, #{appliedAt})} is what actually guarantees this --
     * deliberately NOT a Java-side "already applied?" check-then-write, which would be
     * a check-then-act race under concurrent requests (two requests could both read
     * applied=false, then both write, the second silently clobbering the first's
     * timestamp). The database evaluates {@code COALESCE} against the row's CURRENT
     * value under the row lock this {@code UPDATE} takes, so it is correct regardless
     * of how many concurrent callers reach it -- correctness lives in this one SQL
     * statement, not in whichever service happens to call it.
     */
    @Update("""
            UPDATE jobs
            SET applied = TRUE,
                applied_at = COALESCE(applied_at, #{appliedAt}),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    int markApplied(@Param("id") Long id, @Param("userId") Long userId, @Param("appliedAt") LocalDateTime appliedAt);

    /** Reverses a mistaken confirmation. Scoped by {@code userId}, same as {@link #markApplied}. */
    @Update("""
            UPDATE jobs
            SET applied = FALSE,
                applied_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    int clearApplied(@Param("id") Long id, @Param("userId") Long userId);

    @Update("""
            UPDATE jobs
            SET company = #{company},
                title = #{title},
                location = #{location},
                job_url = #{jobUrl},
                description = #{description},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    int update(Job job);

    @Delete("""
            DELETE FROM jobs
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
