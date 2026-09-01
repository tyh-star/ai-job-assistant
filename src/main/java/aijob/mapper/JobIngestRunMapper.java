package aijob.mapper;

import aijob.entity.JobIngestRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface JobIngestRunMapper {

    String COLUMNS = """
            id, source_id, started_at, finished_at, status, fetched_count,
            inserted_count, updated_count, unchanged_count, expired_count,
            failed_count, error_summary
            """;

    @Insert("""
            INSERT INTO job_ingest_runs (source_id, started_at, status)
            VALUES (#{sourceId}, #{startedAt}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(JobIngestRun run);

    @Update("""
            UPDATE job_ingest_runs
            SET finished_at = #{finishedAt},
                status = #{status},
                fetched_count = #{fetchedCount},
                inserted_count = #{insertedCount},
                updated_count = #{updatedCount},
                unchanged_count = #{unchangedCount},
                expired_count = #{expiredCount},
                failed_count = #{failedCount},
                error_summary = #{errorSummary}
            WHERE id = #{id}
            """)
    int finish(JobIngestRun run);

    @Select("SELECT " + COLUMNS + " FROM job_ingest_runs WHERE id = #{id}")
    JobIngestRun findById(@Param("id") Long id);

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM job_ingest_runs
            WHERE source_id = #{sourceId}
            ORDER BY started_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<JobIngestRun> findRecentForSource(@Param("sourceId") Long sourceId, @Param("limit") int limit);

    /** Housekeeping: keeps run history bounded on a small instance. */
    @Update("""
            DELETE FROM job_ingest_runs
            WHERE started_at < #{cutoff}
            """)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
