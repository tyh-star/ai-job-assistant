package aijob.mapper;

import aijob.entity.ResumeEvidenceItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ResumeEvidenceMapper {

    @Insert("""
            INSERT INTO resume_evidence_items (
                user_id,
                resume_id,
                topic_key,
                topic_label,
                category,
                answer_status,
                entry_key,
                entry_label,
                related_node_ids_json,
                context_text,
                action_text,
                result_text,
                metric_text
            )
            VALUES (
                #{userId},
                #{resumeId},
                #{topicKey},
                #{topicLabel},
                #{category},
                #{answerStatus},
                #{entryKey},
                #{entryLabel},
                #{relatedNodeIdsJson},
                #{contextText},
                #{actionText},
                #{resultText},
                #{metricText}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ResumeEvidenceItem item);

    @Update("""
            UPDATE resume_evidence_items
            SET topic_label = #{topicLabel},
                category = #{category},
                answer_status = #{answerStatus},
                entry_label = #{entryLabel},
                related_node_ids_json = #{relatedNodeIdsJson},
                context_text = #{contextText},
                action_text = #{actionText},
                result_text = #{resultText},
                metric_text = #{metricText}
            WHERE user_id = #{userId}
              AND resume_id = #{resumeId}
              AND topic_key = #{topicKey}
              AND entry_key = #{entryKey}
            """)
    int updateByTopicAndEntry(ResumeEvidenceItem item);

    @Select("""
            SELECT *
            FROM resume_evidence_items
            WHERE user_id = #{userId}
              AND resume_id = #{resumeId}
            ORDER BY updated_at DESC, id DESC
            """)
    List<ResumeEvidenceItem> findByResumeAndUser(
            @Param("resumeId") Long resumeId,
            @Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM resume_evidence_items
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    ResumeEvidenceItem findByIdAndUser(
            @Param("id") Long id,
            @Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM resume_evidence_items
            WHERE user_id = #{userId}
              AND resume_id = #{resumeId}
              AND topic_key = #{topicKey}
              AND entry_key = #{entryKey}
            """)
    ResumeEvidenceItem findByTopicAndEntry(
            @Param("userId") Long userId,
            @Param("resumeId") Long resumeId,
            @Param("topicKey") String topicKey,
            @Param("entryKey") String entryKey);

    @Delete("""
            DELETE FROM resume_evidence_items
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    int deleteByIdAndUser(
            @Param("id") Long id,
            @Param("userId") Long userId);

    @Delete("""
            DELETE FROM resume_evidence_items
            WHERE user_id = #{userId}
              AND resume_id = #{resumeId}
            """)
    int deleteByResumeAndUser(
            @Param("resumeId") Long resumeId,
            @Param("userId") Long userId);

    @Delete("""
            DELETE FROM resume_evidence_items
            WHERE user_id = #{userId}
              AND resume_id = #{resumeId}
              AND topic_key = #{topicKey}
              AND answer_status = 'CONFIRMED_NOT_USED'
            """)
    int deleteNotUsedByTopic(
            @Param("userId") Long userId,
            @Param("resumeId") Long resumeId,
            @Param("topicKey") String topicKey);
}
