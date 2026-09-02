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

    /**
     * Replaces the links a catalog row displays. Kept separate from {@link
     * #update(JobCatalogEntry)} because the links are not owned by whichever board was
     * collected last: they are re-derived from the preferred source record, so a second
     * board merging into this row cannot overwrite the first board's apply link.
     */
    @Update("""
            UPDATE job_catalog
            SET canonical_url = #{canonicalUrl},
                apply_url = #{applyUrl},
                canonical_url_norm = #{canonicalUrlNorm},
                apply_url_norm = #{applyUrlNorm}
            WHERE id = #{id}
            """)
    int updateEffectiveLinks(@Param("id") Long id,
                             @Param("canonicalUrl") String canonicalUrl,
                             @Param("applyUrl") String applyUrl,
                             @Param("canonicalUrlNorm") String canonicalUrlNorm,
                             @Param("applyUrlNorm") String applyUrlNorm);

    /**
     * The only cross-source de-duplication lookup: a URL that provably names one
     * opening, matched against either URL column because one board's canonical link is
     * another's apply link. Callers pass
     * {@code CatalogFingerprint.identityUrl(...)}, which is null for a careers landing
     * page, so a generic URL can never become a merge key.
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

    /**
     * Lists other postings that share a company+title+location fingerprint.
     *
     * <p>This is a review aid, not a de-duplication lookup, and nothing in ingestion
     * calls it. One company routinely runs several distinct openings with the same
     * title in the same city under different requisition numbers, so a shared
     * fingerprint means "worth a human look", never "merge these".
     */
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM job_catalog
            WHERE fingerprint = #{fingerprint}
              AND id <> #{excludeId}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<JobCatalogEntry> findSuspectedDuplicates(@Param("fingerprint") String fingerprint,
                                                  @Param("excludeId") Long excludeId,
                                                  @Param("limit") int limit);

    /** Sample of the newest live postings, for the operator's duplicate review. */
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM job_catalog
            WHERE status = 'ACTIVE'
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<JobCatalogEntry> findRecentActive(@Param("limit") int limit);

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

    /**
     * Rewrites only the derived location fields.
     *
     * <p>For a posting whose source content did not change but whose city/region/country
     * were produced by older parsing rules. It deliberately does not touch
     * { payload_hash}, { last_seen_at} or anything the employer published --
     * this repairs what WE derived, and dressing it up as an update would misreport who
     * changed what.
     */
    @Update("""
            UPDATE job_catalog
            SET city = #{city},
                region = #{region},
                country_code = #{countryCode}
            WHERE id = #{id}
            """)
    int updateDerivedLocation(@Param("id") Long id,
                              @Param("city") String city,
                              @Param("region") String region,
                              @Param("countryCode") String countryCode);

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
