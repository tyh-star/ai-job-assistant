package aijob.service;

import aijob.dto.ResumeResponse;
import aijob.dto.builder.CareerMaterialsResponse;
import aijob.dto.builder.CreateResumeBuilderDraftRequest;
import aijob.dto.builder.GenerateResumeBuilderDraftRequest;
import aijob.dto.builder.NormalizedResume;
import aijob.dto.builder.ResumeBuilderDraftResponse;
import aijob.dto.builder.ResumeBuilderDraftSummary;
import aijob.dto.builder.UpdateResumeBuilderDraftRequest;

import java.util.List;

/**
 * 通用简历生成器 (resume builder): multiple named drafts per user, a normalized
 * preview shared with DOCX generation, deterministic career materials, and generation
 * into a first-class {@code GENERATED}-origin resume visible in "我的简历". Every
 * method resolves the caller's own account from {@code email} and every draft
 * id-scoped method enforces ownership as {@code id + userId} together -- a draft
 * belonging to another account is indistinguishable from one that does not exist (see
 * ResumeBuilderDraftNotFoundException).
 */
public interface ResumeBuilderService {

    ResumeBuilderDraftResponse createDraft(String email, CreateResumeBuilderDraftRequest request);

    List<ResumeBuilderDraftSummary> listDrafts(String email);

    ResumeBuilderDraftResponse getDraft(String email, Long draftId);

    /** Throws ResumeBuilderDraftConflictException (409) when {@code request.version}
     * is stale -- the caller must reload rather than silently overwrite. */
    ResumeBuilderDraftResponse updateDraft(String email, Long draftId, UpdateResumeBuilderDraftRequest request);

    ResumeBuilderDraftResponse duplicateDraft(String email, Long draftId);

    void deleteDraft(String email, Long draftId);

    /** The SAME normalized model {@link ResumeBuilderDocxService} renders -- see
     * NormalizedResume's javadoc. */
    NormalizedResume previewDraft(String email, Long draftId);

    /** Idempotent on {@code request.idempotencyKey} -- see
     * schema/resume_builder.sql's javadoc. Never calls OpenAI; the generated resume's
     * origin is {@code ResumeOrigin.GENERATED} and it appears in "我的简历" like any
     * other resume. */
    ResumeResponse generateFromDraft(String email, Long draftId, GenerateResumeBuilderDraftRequest request);

    /** Deterministic, zero-OpenAI-call optional materials -- see
     * CareerMaterialsGenerator. Computed fresh from the draft's current saved content,
     * never persisted. */
    CareerMaterialsResponse getCareerMaterials(String email, Long draftId);
}
