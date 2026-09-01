package aijob.mapper;

import aijob.entity.UserSavedCatalogJob;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserSavedCatalogJobMapper {

    String COLUMNS = """
            id, user_id, catalog_job_id, personal_job_id, created_at
            """;

    @Insert("""
            INSERT INTO user_saved_catalog_jobs (user_id, catalog_job_id, personal_job_id)
            VALUES (#{userId}, #{catalogJobId}, #{personalJobId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserSavedCatalogJob mapping);

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM user_saved_catalog_jobs
            WHERE user_id = #{userId}
              AND catalog_job_id = #{catalogJobId}
            LIMIT 1
            """)
    UserSavedCatalogJob findByUserAndCatalogJob(@Param("userId") Long userId,
                                                @Param("catalogJobId") Long catalogJobId);

    @Select("""
            <script>
            SELECT catalog_job_id
            FROM user_saved_catalog_jobs
            WHERE user_id = #{userId}
              AND catalog_job_id IN
              <foreach item="id" collection="catalogJobIds" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> findSavedCatalogJobIds(@Param("userId") Long userId,
                                      @Param("catalogJobIds") List<Long> catalogJobIds);
}
