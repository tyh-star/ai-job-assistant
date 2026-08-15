package aijob.mapper;

import aijob.entity.Resume;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ResumeMapper {

    @Insert("""
            INSERT INTO resumes (user_id, file_name, file_type, file_path, extracted_text)
            VALUES (#{userId}, #{fileName}, #{fileType}, #{filePath}, #{extractedText})
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

    @Delete("""
            DELETE FROM resumes
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
