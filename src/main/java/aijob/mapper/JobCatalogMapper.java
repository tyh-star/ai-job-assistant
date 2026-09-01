package aijob.mapper;

import aijob.catalog.CatalogJobSourceRow;
import aijob.catalog.JobCatalogSearchQuery;
import aijob.entity.JobCatalogEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface JobCatalogMapper {

    String COLUMNS = """
            id, title, company_name, description_text, description_html,
            location_text, city, region, country_code, remote_type, employment_type,
            salary_min, salary_max, currency, canonical_url, apply_url,
            canonical_url_norm, apply_url_norm,
            posted_at, expires_at, first_seen_at, last_seen_at, status, fingerprint,
            created_at, updated_at
            """;

    @Insert("""
            INSERT INTO job_catalog (
                title, company_name, description_text, description_html,
                location_text, city, region, country_code, remote_type, employment_type,
                salary_min, salary_max, currency, canonical_url, apply_url,
                canonical_url_norm, apply_url_norm,
                posted_at, expires_at, first_seen_at, last_seen_at, status, fingerprint
            )
            VALUES (
                #{title}, #{companyName}, #{descriptionText}, #{descriptionHtml},
                #{locationText}, #{city}, #{region}, #{countryCode}, #{remoteType}, #{employmentType},
                #{salaryMin}, #{salaryMax}, #{currency}, #{canonicalUrl}, #{applyUrl},
                #{canonicalUrlNorm}, #{applyUrlNorm},
                #{postedAt}, #{expiresAt}, #{firstSeenAt}, #{lastSeenAt}, #{status}, #{fingerprint}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(JobCatalogEntry entry);

    @Update("""
            UPDATE job_catalog
            SET title = #{title},
                company_name = #{companyName},
                description_text = #{descriptionText},
                description_html = #{descriptionHtml},
                location_text = #{locationText},
                city = #{city},
                region = #{region},
                country_code = #{countryCode},
                remote_type = #{remoteType},
                employment_type = #{employmentType},
                salary_min = #{salaryMin},
                salary_max = #{salaryMax},
                currency = #{currency},
                canonical_url = #{canonicalUrl},
                apply_url = #{applyUrl},
                canonical_url_norm = #{canonicalUrlNorm},
                apply_url_norm = #{applyUrlNorm},
                posted_at = #{postedAt},
                expires_at = #{expiresAt},
                last_seen_at = #{lastSeenAt},
                status = #{status},
                fingerprint = #{fingerprint}
            WHERE id = #{id}
            """)
    int update(JobCatalogEntry entry);

    @Select("SELECT " + COLUMNS + " FROM job_catalog WHERE id = #{id}")
    JobCatalogEntry findById(@Param("id") Long id);

    /** De-duplication priority 1: the same normalized apply URL. */
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM job_catalog
            WHERE apply_url_norm = #{applyUrlNorm}
            ORDER BY id
            LIMIT 1
            """)
    JobCatalogEntry findByApplyUrlNorm(@Param("applyUrlNorm") String applyUrlNorm);

    /**
     * De-duplication priority 2: the same normalized canonical URL, matched against
     * either URL column because one board's canonical link is another's apply link.
     */
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM job_catalog
            WHERE canonical_url_norm = #{urlNorm} OR apply_url_norm = #{urlNorm}
            ORDER BY id
            LIMIT 1
            """)
    JobCatalogEntry findByAnyUrlNorm(@Param("urlNorm") String urlNorm);

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM job_catalog
            WHERE fingerprint = #{fingerprint}
            ORDER BY id
            LIMIT 1
            """)
    JobCatalogEntry findByFingerprint(@Param("fingerprint") String fingerprint);

    /**
     * Soft-expires a still-ACTIVE posting. Rows are never physically deleted, so a
     * posting that reappears can be restored.
     */
    @Update("""
            UPDATE job_catalog
            SET status = 'EXPIRED'
            WHERE id = #{id}
              AND status = 'ACTIVE'
            """)
    int markExpired(@Param("id") Long id);

    @Update("""
            UPDATE job_catalog
            SET status = 'ACTIVE',
                last_seen_at = #{lastSeenAt}
            WHERE id = #{id}
              AND status <> 'ACTIVE'
            """)
    int restoreActive(@Param("id") Long id, @Param("lastSeenAt") LocalDateTime lastSeenAt);

    @Update("""
            UPDATE job_catalog
            SET last_seen_at = #{lastSeenAt}
            WHERE id = #{id}
            """)
    int touchLastSeen(@Param("id") Long id, @Param("lastSeenAt") LocalDateTime lastSeenAt);

    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
            FROM job_catalog
            <where>
              <if test="statuses != null and statuses.size() > 0">
                AND status IN
                <foreach item="s" collection="statuses" open="(" separator="," close=")">#{s}</foreach>
              </if>
              <if test="useFullText and keywordBoolean != null">
                AND MATCH (title, company_name, description_text) AGAINST (#{keywordBoolean} IN BOOLEAN MODE)
              </if>
              <if test="!useFullText and keywordLike != null">
                AND (LOWER(title) LIKE #{keywordLike}
                     OR LOWER(company_name) LIKE #{keywordLike}
                     OR LOWER(description_text) LIKE #{keywordLike})
              </if>
              <if test="location != null">
                AND LOWER(location_text) LIKE #{location}
              </if>
              <if test="countryCode != null">
                AND country_code = #{countryCode}
              </if>
              <if test="remoteType != null">
                AND remote_type = #{remoteType}
              </if>
              <if test="employmentType != null">
                AND employment_type = #{employmentType}
              </if>
              <if test="postedAfter != null">
                AND posted_at IS NOT NULL AND posted_at &gt;= #{postedAfter}
              </if>
            </where>
            <choose>
              <when test="useFullText and keywordBoolean != null and sort == 'RELEVANCE'">
                ORDER BY MATCH (title, company_name, description_text) AGAINST (#{keywordBoolean} IN BOOLEAN MODE) DESC,
                         posted_at DESC, id DESC
              </when>
              <otherwise>
                ORDER BY posted_at DESC, id DESC
              </otherwise>
            </choose>
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<JobCatalogEntry> search(JobCatalogSearchQuery query);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM job_catalog
            <where>
              <if test="statuses != null and statuses.size() > 0">
                AND status IN
                <foreach item="s" collection="statuses" open="(" separator="," close=")">#{s}</foreach>
              </if>
              <if test="useFullText and keywordBoolean != null">
                AND MATCH (title, company_name, description_text) AGAINST (#{keywordBoolean} IN BOOLEAN MODE)
              </if>
              <if test="!useFullText and keywordLike != null">
                AND (LOWER(title) LIKE #{keywordLike}
                     OR LOWER(company_name) LIKE #{keywordLike}
                     OR LOWER(description_text) LIKE #{keywordLike})
              </if>
              <if test="location != null">
                AND LOWER(location_text) LIKE #{location}
              </if>
              <if test="countryCode != null">
                AND country_code = #{countryCode}
              </if>
              <if test="remoteType != null">
                AND remote_type = #{remoteType}
              </if>
              <if test="employmentType != null">
                AND employment_type = #{employmentType}
              </if>
              <if test="postedAfter != null">
                AND posted_at IS NOT NULL AND posted_at &gt;= #{postedAfter}
              </if>
            </where>
            </script>
            """)
    long countSearch(JobCatalogSearchQuery query);

    /** Source attribution for a page of results. Every posting keeps its origin. */
    @Select("""
            <script>
            SELECT r.catalog_job_id AS catalogJobId,
                   s.provider_type  AS providerType,
                   s.display_name   AS displayName
            FROM job_source_records r
            JOIN job_sources s ON s.id = r.source_id
            WHERE r.catalog_job_id IN
            <foreach item="id" collection="catalogJobIds" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY r.catalog_job_id, r.id
            </script>
            """)
    List<CatalogJobSourceRow> findSourcesForCatalogJobs(@Param("catalogJobIds") List<Long> catalogJobIds);
}
