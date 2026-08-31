package aijob.tailor.v3;

import aijob.config.TailorEngineProperties;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code AIJOB_TAILOR_ENGINE} without changing the V2 tailor path.
 *
 * <p>V3-2 injects this router into {@code JobServiceImpl}. Production uses V3 BASIC
 * only when {@code AIJOB_TAILOR_ENGINE=V3}. Default V2 still calls
 * {@code runConservativeTailor} exclusively.
 *
 * <p>Roadmap (not in V3-1):
 * <ul>
 *   <li>V3-2 MVP: shared entry-level generator + equal-count nodeId Writer adapter
 *       (no add/delete/reorder of DOCX paragraphs).</li>
 *   <li>Later: {@code V3EntryRangeWriter} for safe add/delete/reorder of bullets
 *       inside a verified PROJECT/EXPERIENCE range while preserving heading, dates,
 *       links, indentation, and bullet formatting. Until that writer exists, the
 *       product is V3 MVP, not complete entry-level restructuring.</li>
 * </ul>
 */
@Component
public class TailorEngineRouter {

    private final TailorEngineProperties properties;

    public TailorEngineRouter(TailorEngineProperties properties) {
        this.properties = properties == null ? TailorEngineProperties.defaults() : properties;
    }

    public TailorEngine engine() {
        return properties.engine();
    }

    public boolean useV2() {
        return engine() == TailorEngine.V2;
    }

    public boolean useV3() {
        return engine() == TailorEngine.V3;
    }
}
