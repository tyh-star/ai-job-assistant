package aijob.util;

import aijob.dto.builder.CareerMaterialsResponse;
import aijob.dto.builder.ResumeBuilderDraftContent;
import aijob.dto.builder.ResumeBuilderDraftContent.BasicInfo;
import aijob.dto.builder.ResumeBuilderDraftContent.CareerMaterials;
import aijob.dto.builder.ResumeBuilderDraftContent.JobTarget;
import aijob.dto.builder.ResumeBuilderDraftContent.SkillCategory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the five optional career materials from ONLY the facts the user typed on the
 * "职业材料" tab (plus basic info / job target, already entered elsewhere in the same
 * draft) -- template composition, string joins, nothing else. Deliberately has NO
 * dependency on {@link aijob.service.AiResumeService} or any OpenAI-calling class --
 * that is what makes "never calls OpenAI, consumes no AI quota, cannot invent an
 * experience or a number" true by construction, not just by convention. A field that
 * was not filled in never appears; instead the relevant paragraph names what to add,
 * in the caller's locale.
 */
@Component
public class CareerMaterialsGenerator {

    public CareerMaterialsResponse generate(ResumeBuilderDraftContent content, String locale) {
        Labels l = Labels.forLocale(locale);
        BasicInfo info = content.getBasicInfo() == null ? new BasicInfo() : content.getBasicInfo();
        JobTarget target = content.getJobTarget() == null ? new JobTarget() : content.getJobTarget();
        CareerMaterials materials = content.getCareerMaterials() == null
                ? new CareerMaterials() : content.getCareerMaterials();

        CareerMaterialsResponse response = new CareerMaterialsResponse();
        response.setPositioningSummary(positioningSummary(info, target, content.getSkillCategories(), l));
        response.setSelfIntroduction(selfIntroduction(info, target, materials, l));
        response.setAiReflection(aiReflection(materials, l));
        response.setStrengthsAndWeaknesses(strengthsAndWeaknesses(content.getSkillCategories(), materials, l));
        response.setDevelopmentPlan(developmentPlan(materials, l));
        return response;
    }

    private String positioningSummary(BasicInfo info, JobTarget target, List<SkillCategory> categories, Labels l) {
        if (blank(target.getTargetTitle())) {
            return l.needTargetTitle;
        }
        List<String> clauses = new ArrayList<>();
        String who = blank(info.getFullName()) ? l.genericI : info.getFullName();
        clauses.add(l.targeting.formatted(who, target.getTargetTitle()));
        if (!blank(target.getTargetIndustry())) {
            clauses.add(l.inIndustry.formatted(target.getTargetIndustry()));
        }
        if (!blank(target.getJobLevel())) {
            clauses.add(l.atLevel.formatted(target.getJobLevel()));
        }
        List<String> topSkills = topSkills(categories, 6);
        StringBuilder sb = new StringBuilder(String.join(" ", clauses));
        if (!topSkills.isEmpty()) {
            sb.append(" ").append(l.coreStrengths.formatted(String.join(", ", topSkills)));
        }
        return sb.toString();
    }

    private String selfIntroduction(BasicInfo info, JobTarget target, CareerMaterials materials, Labels l) {
        List<String> facts = nonBlank(materials.getSelfIntroFacts());
        if (facts.isEmpty()) {
            return l.needSelfIntroFacts;
        }
        List<String> sentences = new ArrayList<>();
        String who = blank(info.getFullName()) ? l.genericI : info.getFullName();
        sentences.add(l.greeting.formatted(who));
        sentences.addAll(facts);
        if (!blank(target.getTargetTitle())) {
            sentences.add(l.closing.formatted(target.getTargetTitle()));
        }
        return String.join(" ", sentences);
    }

    private String aiReflection(CareerMaterials materials, Labels l) {
        List<String> scenarios = nonBlank(materials.getAiUsageScenarios());
        List<String> strengths = nonBlank(materials.getAiStrengths());
        List<String> risks = nonBlank(materials.getAiRisks());
        List<String> verification = nonBlank(materials.getAiVerificationApproach());
        if (scenarios.isEmpty() && strengths.isEmpty() && risks.isEmpty() && verification.isEmpty()) {
            return l.needAiFacts;
        }
        List<String> paragraphs = new ArrayList<>();
        addSection(paragraphs, l.aiUsage, scenarios);
        addSection(paragraphs, l.aiStrengthsLabel, strengths);
        addSection(paragraphs, l.aiRisksLabel, risks);
        addSection(paragraphs, l.aiVerification, verification);
        return String.join("\n\n", paragraphs);
    }

    private String strengthsAndWeaknesses(List<SkillCategory> categories, CareerMaterials materials, Labels l) {
        List<String> strengths = new ArrayList<>(nonBlank(materials.getAiStrengths()));
        List<String> topSkills = topSkills(categories, 8);
        strengths.addAll(topSkills);
        List<String> weaknesses = nonBlank(materials.getSkillsToImprove());
        if (strengths.isEmpty() && weaknesses.isEmpty()) {
            return l.needStrengthsFacts;
        }
        List<String> paragraphs = new ArrayList<>();
        if (!strengths.isEmpty()) {
            paragraphs.add(l.strengthsLabel + ": " + String.join(", ", strengths));
        }
        if (!weaknesses.isEmpty()) {
            paragraphs.add(l.weaknessesLabel + ": " + String.join(", ", weaknesses));
        }
        return String.join("\n\n", paragraphs);
    }

    private String developmentPlan(CareerMaterials materials, Labels l) {
        String oneYear = blankToEmpty(materials.getOneYearGoal());
        String threeYear = blankToEmpty(materials.getThreeYearGoal());
        if (oneYear.isBlank() && threeYear.isBlank()) {
            return l.needGoals;
        }
        List<String> paragraphs = new ArrayList<>();
        if (!oneYear.isBlank()) {
            paragraphs.add(l.oneYearLabel + ": " + oneYear);
        }
        if (!threeYear.isBlank()) {
            paragraphs.add(l.threeYearLabel + ": " + threeYear);
        }
        return String.join("\n\n", paragraphs);
    }

    private static void addSection(List<String> paragraphs, String label, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        paragraphs.add(label + ":\n" + String.join("\n", items.stream().map(i -> "- " + i).toList()));
    }

    private static List<String> topSkills(List<SkillCategory> categories, int limit) {
        List<String> skills = new ArrayList<>();
        if (categories != null) {
            for (SkillCategory category : categories) {
                if (category.getSkills() == null) {
                    continue;
                }
                for (String skill : category.getSkills()) {
                    if (skill != null && !skill.isBlank()) {
                        skills.add(skill.trim());
                    }
                    if (skills.size() >= limit) {
                        return skills;
                    }
                }
            }
        }
        return skills;
    }

    private static List<String> nonBlank(List<String> values) {
        return values == null ? List.of()
                : values.stream().filter(v -> v != null && !v.isBlank()).toList();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class Labels {
        String needTargetTitle, needSelfIntroFacts, needAiFacts, needStrengthsFacts, needGoals;
        String targeting, inIndustry, atLevel, coreStrengths, genericI, greeting, closing;
        String aiUsage, aiStrengthsLabel, aiRisksLabel, aiVerification;
        String strengthsLabel, weaknessesLabel, oneYearLabel, threeYearLabel;

        static Labels forLocale(String locale) {
            Labels l = new Labels();
            boolean zh = "zh-CN".equals(locale);
            l.needTargetTitle = zh
                    ? "请先在“任职意向”中填写目标职位，才能生成职业定位摘要。"
                    : "Add a target job title on the Job Target tab to generate a positioning summary.";
            l.needSelfIntroFacts = zh
                    ? "请先在“职业材料”中填写用于自我介绍的关键事实。"
                    : "Add a few key facts for your self-introduction on the Career Materials tab.";
            l.needAiFacts = zh
                    ? "请先在“职业材料”中填写 AI 编程使用场景/优点/风险/验证方式中的至少一项。"
                    : "Add at least one AI usage scenario, strength, risk, or verification approach on the "
                    + "Career Materials tab.";
            l.needStrengthsFacts = zh
                    ? "请先填写技能或“想加强的技能”，才能生成优势与不足。"
                    : "Add some skills or a few things you want to improve to generate strengths & weaknesses.";
            l.needGoals = zh
                    ? "请先在“职业材料”中填写一年或三年发展目标。"
                    : "Add a one-year or three-year goal on the Career Materials tab.";
            l.targeting = zh ? "%s 的目标职位是 %s。" : "%s is targeting %s roles.";
            l.inIndustry = zh ? "目标行业：%s。" : "Target industry: %s.";
            l.atLevel = zh ? "职级：%s。" : "Level: %s.";
            l.coreStrengths = zh ? "核心技能：%s。" : "Core skills: %s.";
            l.genericI = zh ? "该候选人" : "This candidate";
            l.greeting = zh ? "你好，我是%s。" : "Hi, I'm %s.";
            l.closing = zh ? "我目前在寻找%s相关的机会。" : "I'm currently looking for %s opportunities.";
            l.aiUsage = zh ? "AI 编程使用场景" : "AI coding usage scenarios";
            l.aiStrengthsLabel = zh ? "AI 编程的优点" : "Strengths of using AI for coding";
            l.aiRisksLabel = zh ? "AI 编程的风险" : "Risks of using AI for coding";
            l.aiVerification = zh ? "如何验证 AI 生成的代码" : "How I verify AI-generated code";
            l.strengthsLabel = zh ? "优势" : "Strengths";
            l.weaknessesLabel = zh ? "待加强" : "Areas to improve";
            l.oneYearLabel = zh ? "一年目标" : "One-year goal";
            l.threeYearLabel = zh ? "三年目标" : "Three-year goal";
            return l;
        }
    }
}
