package aijob.mapper;

import aijob.entity.Resume;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ResumeMapper {

    @Insert("""
            INSERT INTO resumes (
                user_id,
                file_name,
                file_type,
                file_path,
                extracted_text,
                parent_resume_id,
                canonical_resume_id,
                origin,
                review_status,
                structured_json,
                layout_json,
                extraction_status
            )
            VALUES (
                #{userId},
                #{fileName},
                #{fileType},
                #{filePath},
                #{extractedText},
                #{parentResumeId},
                #{canonicalResumeId},
                #{origin},
                #{reviewStatus},
                #{structuredJson},
                #{layoutJson},
                #{extractionStatus}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Resume resume);

    @Select("SELECT * FROM resumes WHERE id = #{id}")
    Resume findById(Long id);

    @Select("""
            SELECT * FROM resumes
            WHERE user_id = #{userId}
            ORDER BY created_at DESC
            """)
    List<Resume> findByUserId(Long userId);

    @Update("""
            UPDATE resumes
            SET structured_json = #{structuredJson},
                review_status = #{reviewStatus}
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    int updateStructure(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("structuredJson") String structuredJson,
            @Param("reviewStatus") String reviewStatus);

    @Update("""
            UPDATE resumes
            SET layout_json = #{layoutJson},
                extraction_status = #{extractionStatus},
                structured_json = #{structuredJson},
                review_status = #{reviewStatus}
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    int updateLayoutAndStructure(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("layoutJson") String layoutJson,
            @Param("extractionStatus") String extractionStatus,
            @Param("structuredJson") String structuredJson,
            @Param("reviewStatus") String reviewStatus);

    @Update("""
            UPDATE resumes
            SET canonical_resume_id = #{canonicalResumeId},
                review_status = #{reviewStatus}
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    int updateCanonicalParent(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("canonicalResumeId") Long canonicalResumeId,
            @Param("reviewStatus") String reviewStatus);

    @Update("""
            UPDATE resumes
            SET file_name = #{fileName},
                file_type = #{fileType},
                file_path = #{filePath},
                extracted_text = #{extractedText},
                review_status = #{reviewStatus},
                extraction_status = #{extractionStatus}
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    int updateCanonicalChildFile(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("fileName") String fileName,
            @Param("fileType") String fileType,
            @Param("filePath") String filePath,
            @Param("extractedText") String extractedText,
            @Param("reviewStatus") String reviewStatus,
            @Param("extractionStatus") String extractionStatus);

    @Select("""
            SELECT * FROM resumes
            WHERE parent_resume_id = #{parentResumeId}
              AND user_id = #{userId}
            """)
    List<Resume> findByParentResumeIdAndUserId(
            @Param("parentResumeId") Long parentResumeId,
            @Param("userId") Long userId);

    @Update("""
            UPDATE resumes
            SET canonical_resume_id = NULL
            WHERE user_id = #{userId}
              AND canonical_resume_id = #{resumeId}
            """)
    int clearCanonicalResumeId(
            @Param("userId") Long userId,
            @Param("resumeId") Long resumeId);

    @Update("""
            UPDATE resumes
            SET parent_resume_id = NULL
            WHERE user_id = #{userId}
              AND parent_resume_id = #{resumeId}
            """)
    int clearParentResumeId(
            @Param("userId") Long userId,
            @Param("resumeId") Long resumeId);

    @Delete("""
            DELETE FROM resumes
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
