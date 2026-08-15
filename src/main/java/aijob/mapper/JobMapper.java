package aijob.mapper;

import aijob.entity.Job;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface JobMapper {

    @Insert("""
            INSERT INTO jobs (
                user_id, company, title, location, job_url,
                description, source, status
            )
            VALUES (
                #{userId}, #{company}, #{title}, #{location}, #{jobUrl},
                #{description}, #{source}, #{status}
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
