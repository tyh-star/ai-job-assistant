package aijob.mapper;

import aijob.dto.FeedbackSummaryResponse;
import aijob.entity.SiteFeedback;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FeedbackMapper {

    @Select("SELECT * FROM site_feedback WHERE user_id = #{userId}")
    SiteFeedback findByUserId(Long userId);

    @Insert("""
            INSERT INTO site_feedback (user_id, rating, comment)
            VALUES (#{userId}, #{rating}, #{comment})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SiteFeedback feedback);

    @Update("""
            UPDATE site_feedback
            SET rating = #{rating},
                comment = #{comment},
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
            """)
    int updateByUserId(@Param("userId") Long userId, @Param("rating") int rating, @Param("comment") String comment);

    @Delete("DELETE FROM site_feedback WHERE user_id = #{userId}")
    int deleteByUserId(Long userId);

    /**
     * The PUBLIC summary query -- deliberately selects ONLY these two aggregates,
     * never a raw row, so there is no column here that could ever leak a user's
     * email, name, or comment text even by a future editing mistake.
     * {@code COALESCE(..., 0)} guarantees a real 0, never SQL NULL, when the table is
     * empty -- {@link FeedbackSummaryResponse}'s fields are primitives specifically so
     * a null can never silently reach the API response.
     */
    @Select("SELECT COALESCE(AVG(rating), 0) AS average_rating, COUNT(*) AS reviewer_count FROM site_feedback")
    FeedbackSummaryResponse getSummary();
}
