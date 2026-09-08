package aijob.mapper;

import aijob.entity.JobSearchHistory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface JobSearchHistoryMapper {

    @Insert("""
            INSERT INTO job_search_history (
                user_id, keywords, city, city_country, country, work_mode, employment_type,
                posted_within, sort_by, criteria_hash, search_count, created_at, last_searched_at
            ) VALUES (
                #{userId}, #{keywords}, #{city}, #{cityCountry}, #{country}, #{workMode},
                #{employmentType}, #{postedWithin}, #{sortBy}, #{criteriaHash}, 1,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            ON DUPLICATE KEY UPDATE
                search_count = search_count + 1,
                last_searched_at = CURRENT_TIMESTAMP
            """)
    int upsert(JobSearchHistory row);

    @Select("""
            SELECT * FROM job_search_history
            WHERE user_id = #{userId}
            ORDER BY last_searched_at DESC, id DESC
            """)
    List<JobSearchHistory> findByUserIdNewestFirst(@Param("userId") Long userId);

    @Select("""
            SELECT id FROM job_search_history
            WHERE user_id = #{userId}
            ORDER BY last_searched_at DESC, id DESC
            """)
    List<Long> findIdsByUserIdNewestFirst(@Param("userId") Long userId);

    @Delete("""
            DELETE FROM job_search_history
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Delete("""
            DELETE FROM job_search_history
            WHERE user_id = #{userId}
            """)
    int deleteAllByUserId(@Param("userId") Long userId);

    @Delete("""
            <script>
            DELETE FROM job_search_history
            WHERE user_id = #{userId}
              AND id IN
              <foreach item="id" collection="ids" open="(" separator="," close=")">
                #{id}
              </foreach>
            </script>
            """)
    int deleteByIdsAndUserId(@Param("ids") List<Long> ids, @Param("userId") Long userId);
}
