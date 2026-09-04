package aijob.service;

import aijob.dto.JobCatalogDetail;
import aijob.dto.JobCatalogSearchRequest;
import aijob.dto.JobCatalogSearchResponse;
import aijob.dto.SaveCatalogJobResponse;

/**
 * Search over the site's job catalog. Two implementations select at boot via
 * {@code aijob.job-catalog.provider} ({@code freehire}, the default, or
 * {@code local_catalog}, opt-in) — exactly one is ever registered as this bean, so a
 * caller of this interface cannot reach the other provider by accident.
 *
 * <ul>
 *   <li>{@code freehire} — calls FreeHire's own hosted API (freehire.me/api/v1) for
 *       every search, detail read and save. Nothing is collected or mirrored locally;
 *       a save copies exactly the one posting the user chose into their own jobs.</li>
 *   <li>{@code local_catalog} — reads only this deployment's own {@code job_catalog}
 *       table, built by the self-hosted collector. No external call.</li>
 * </ul>
 */
public interface JobCatalogService {

    JobCatalogSearchResponse search(String email, JobCatalogSearchRequest request);

    /**
     * @param catalogJobId an opaque reference as returned by {@link #search}: a bare
     *                     numeric id under {@code local_catalog}, or a
     *                     {@code freehire:<public_slug>} reference under
     *                     {@code freehire}. Never parsed as a number by the caller.
     */
    JobCatalogDetail findDetail(String email, String catalogJobId);

    /**
     * Copies a catalog posting into the caller's own {@code jobs} table. Repeating the
     * call for the same user and posting returns the existing personal job instead of
     * creating another one.
     */
    SaveCatalogJobResponse saveToMyJobs(String email, String catalogJobId);
}
