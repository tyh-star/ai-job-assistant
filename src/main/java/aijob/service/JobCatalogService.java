package aijob.service;

import aijob.dto.CitySuggestion;
import aijob.dto.JobCatalogDetail;
import aijob.dto.JobCatalogSearchRequest;
import aijob.dto.JobCatalogSearchResponse;
import aijob.dto.SaveCatalogJobResponse;

import java.util.List;

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

    /**
     * @param email the caller's email, or {@code null} for an anonymous (unauthenticated)
     *              search -- public browsing of the catalog needs no account. An
     *              anonymous search never looks up or reveals any user's saved/applied
     *              state: every {@code JobCatalogSummary} comes back with
     *              {@code saved=false}, {@code personalJobId=null}, {@code applied=false},
     *              {@code appliedAt=null}, regardless of what any real user has actually
     *              saved or applied to.
     */
    JobCatalogSearchResponse search(String email, JobCatalogSearchRequest request);

    /**
     * @param email        the caller's email, or {@code null} for an anonymous detail
     *                     read -- same public-browsing rule as {@link #search}.
     * @param catalogJobId an opaque reference as returned by {@link #search}: a bare
     *                     numeric id under {@code local_catalog}, or a
     *                     {@code freehire:<public_slug>} reference under
     *                     {@code freehire}. Never parsed as a number by the caller.
     */
    JobCatalogDetail findDetail(String email, String catalogJobId);

    /**
     * Copies a catalog posting into the caller's own {@code jobs} table. Repeating the
     * call for the same user and posting returns the existing personal job instead of
     * creating another one. Requires a real, authenticated user -- {@code email} must
     * not be {@code null}.
     */
    SaveCatalogJobResponse saveToMyJobs(String email, String catalogJobId);

    /**
     * Confirms the user actually submitted an application for this posting -- never
     * called automatically by this backend on the user's behalf; only ever in response
     * to the user's own explicit confirmation after returning from the external apply
     * link. Implicitly saves the posting first (see {@link #saveToMyJobs}) if it was not
     * already saved, since {@code applied}/{@code appliedAt} live on that same personal
     * {@code jobs} row. Idempotent: confirming again just refreshes {@code appliedAt}.
     * Requires a real, authenticated user -- {@code email} must not be {@code null}.
     */
    SaveCatalogJobResponse markApplied(String email, String catalogJobId);

    /**
     * Reverses a mistaken applied-confirmation. A posting that was never saved (and so
     * was never marked applied) has nothing to clear -- reported as not-found, the same
     * way an unsaved posting's detail lookup would be. Requires a real, authenticated
     * user -- {@code email} must not be {@code null}.
     */
    SaveCatalogJobResponse clearApplied(String email, String catalogJobId);

    /**
     * Structured city suggestions for the search form's city facet -- never free text,
     * and never guessed: under {@code freehire} this proxies {@code GET /geo/cities}
     * (see {@code FreeHireClient#searchCities}), so the only values a caller can ever
     * select are ones FreeHire's own index actually carries. {@code local_catalog} has
     * no such city dictionary and always answers an empty list -- not an error, simply
     * no suggestions available for that provider; its own free-text {@code location}
     * filter is unaffected and stays a separate mechanism.
     *
     * @param q       a prefix to search for; blank yields an empty list, not an error
     * @param country an optional ISO 3166-1 alpha-2 code to narrow the search to
     */
    List<CitySuggestion> searchCities(String q, String country);
}
