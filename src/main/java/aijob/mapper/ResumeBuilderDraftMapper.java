package aijob.mapper;

import aijob.entity.ResumeBuilderDraft;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ResumeBuilderDraftMapper {

    @Insert("""
            INSERT INTO resume_builder_drafts (
                user_id, title, template_type, target_role, locale,
                schema_version, draft_json, version
            )
            VALUES (
                #{userId}, #{title}, #{templateType}, #{targetRole}, #{locale},
                #{schemaVersion}, #{draftJson}, 1
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ResumeBuilderDraft draft);

    /** Every read goes through {@code id + user_id} together -- a draft belonging to
     * another account returns null here, indistinguishable from one that does not
     * exist. */
    @Select("SELECT * FROM resume_builder_drafts WHERE id = #{id} AND user_id = #{userId}")
    ResumeBuilderDraft findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Select("""
            SELECT * FROM resume_builder_drafts
            WHERE user_id = #{userId}
            ORDER BY updated_at DESC
            """)
    List<ResumeBuilderDraft> findAllByUserId(@Param("userId") Long userId);

    /**
     * Optimistic-concurrency update: matches zero rows when either the draft is not
     * owned by this user (already 404'd by a prior lookup) or {@code expectedVersion}
     * is stale -- the caller distinguishes those two cases itself (see
     * ResumeBuilderServiceImpl#updateDraft) since it already confirmed ownership before
     * calling this.
     */
    @Update("""
            UPDATE resume_builder_drafts
            SET title = #{title},
                template_type = #{templateType},
                target_role = #{targetRole},
                locale = #{locale},
                schema_version = #{schemaVersion},
                draft_json = #{draftJson},
                version = version + 1
            WHERE id = #{id}
              AND user_id = #{userId}
              AND version = #{expectedVersion}
            """)
    int updateWithVersion(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("title") String title,
            @Param("templateType") String templateType,
            @Param("targetRole") String targetRole,
            @Param("locale") String locale,
            @Param("schemaVersion") Integer schemaVersion,
            @Param("draftJson") String draftJson,
            @Param("expectedVersion") Long expectedVersion);

    @Delete("DELETE FROM resume_builder_drafts WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
