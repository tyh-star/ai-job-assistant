package aijob.mapper;

import aijob.entity.ResumeBuilderGenerationRequest;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ResumeBuilderGenerationRequestMapper {

    @Select("""
            SELECT * FROM resume_builder_generation_requests
            WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey}
            """)
    ResumeBuilderGenerationRequest findByUserIdAndKey(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey);

    /** Throws a DB-unique-constraint violation (surfaced by Spring as
     * DuplicateKeyException) when another request already claimed this
     * (userId, idempotencyKey) pair -- see ResumeBuilderServiceImpl#generateFromDraft
     * for how the loser of that race recovers. */
    @Insert("""
            INSERT INTO resume_builder_generation_requests (user_id, draft_id, idempotency_key, resume_id)
            VALUES (#{userId}, #{draftId}, #{idempotencyKey}, #{resumeId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ResumeBuilderGenerationRequest request);

    @Update("""
            UPDATE resume_builder_generation_requests
            SET resume_id = #{resumeId}
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateResumeId(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("resumeId") Long resumeId);

    /** Called when the draft itself is deleted -- the idempotency-claim bookkeeping row
     * has no purpose once its draft is gone. Never touches {@code resumes}: a resume
     * this claim already produced stays in "我的简历" regardless of the source draft's
     * lifecycle. */
    @Delete("""
            DELETE FROM resume_builder_generation_requests
            WHERE draft_id = #{draftId} AND user_id = #{userId}
            """)
    int deleteByDraftIdAndUserId(
            @Param("draftId") Long draftId,
            @Param("userId") Long userId);
}
