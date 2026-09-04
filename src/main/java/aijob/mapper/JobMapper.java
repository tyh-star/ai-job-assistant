package aijob.mapper;

import aijob.entity.Job;
import org.apache.ibatis.annotations.*;

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
            SELECT external_job_id AS externalJobId, id AS id
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

    /** One row of {@link #findSavedExternalJobIds}: an external id and the personal job it became. */
    record ExternalIdAndPersonalJobId(String externalJobId, Long id) {
    }

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
