package aijob.mapper;

import aijob.entity.TailorProposal;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TailorProposalMapper {

    @Insert("""
            INSERT INTO tailor_proposals (
                user_id, resume_id, job_id, mode, status, expires_at,
                changes_json, rejected_json
            )
            VALUES (
                #{userId}, #{resumeId}, #{jobId}, #{mode}, #{status}, #{expiresAt},
                #{changesJson}, #{rejectedJson}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TailorProposal proposal);

    @Select("SELECT * FROM tailor_proposals WHERE id = #{id}")
    TailorProposal findById(Long id);

    /**
     * Pro Resume Rewrite session step: moves one PRO_REWRITE row owned by the user from
     * the expected status to the next one. 0 rows = another request already moved it.
     */
    @Update("""
            UPDATE tailor_proposals
               SET status = #{toStatus},
                   changes_json = #{changesJson},
                   generated_file_name = #{generatedFileName},
                   generated_file_path = #{generatedFilePath}
             WHERE id = #{id}
               AND user_id = #{userId}
               AND mode = 'PRO_REWRITE'
               AND status = #{fromStatus}
            """)
    int updateRewriteSession(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("changesJson") String changesJson,
            @Param("generatedFileName") String generatedFileName,
            @Param("generatedFilePath") String generatedFilePath
    );

    @Update("""
            UPDATE tailor_proposals
            SET status = 'CONFIRMING',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND user_id = #{userId}
              AND job_id = #{jobId}
              AND status = 'PENDING'
              AND expires_at > UTC_TIMESTAMP()
            """)
    int claimPending(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("jobId") Long jobId
    );

    @Update("""
            UPDATE tailor_proposals
            SET status = 'PENDING',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND status = 'CONFIRMING'
            """)
    int restorePending(@Param("id") Long id);

    @Update("""
            UPDATE tailor_proposals
            SET changes_json = #{changesJson},
                rejected_json = #{rejectedJson},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND status = 'CONFIRMING'
            """)
    int updateConfirmingContents(
            @Param("id") Long id,
            @Param("changesJson") String changesJson,
            @Param("rejectedJson") String rejectedJson
    );

    @Update("""
            UPDATE tailor_proposals
            SET status = 'CONFIRMED',
                generated_file_name = #{generatedFileName},
                generated_file_path = #{generatedFilePath},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND status = 'CONFIRMING'
            """)
    int markConfirmed(
            @Param("id") Long id,
            @Param("generatedFileName") String generatedFileName,
            @Param("generatedFilePath") String generatedFilePath
    );
}
