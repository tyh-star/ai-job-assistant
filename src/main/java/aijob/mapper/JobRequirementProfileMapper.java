package aijob.mapper;

import aijob.entity.JobRequirementProfile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface JobRequirementProfileMapper {

    @Select("""
            SELECT *
            FROM job_requirement_profiles
            WHERE user_id = #{userId}
              AND job_id = #{jobId}
            """)
    JobRequirementProfile findByUserAndJob(
            @Param("userId") Long userId,
            @Param("jobId") Long jobId);

    @Insert("""
            INSERT INTO job_requirement_profiles (
                user_id,
                job_id,
                description_sha256,
                schema_version,
                extraction_model,
                requirements_json
            )
            VALUES (
                #{userId},
                #{jobId},
                #{descriptionSha256},
                #{schemaVersion},
                #{extractionModel},
                #{requirementsJson}
            )
            ON DUPLICATE KEY UPDATE
                description_sha256 = VALUES(description_sha256),
                schema_version = VALUES(schema_version),
                extraction_model = VALUES(extraction_model),
                requirements_json = VALUES(requirements_json)
            """)
    int upsert(JobRequirementProfile profile);
}
