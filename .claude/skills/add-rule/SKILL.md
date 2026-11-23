---
name: add-rule
description: Add a new truthfulness check to the V3 tailoring validator, with tests proving that it rejects the bad rewrite and still accepts legitimate ones.
argument-hint: "<what the rule should reject, with an example>"
disable-model-invocation: true
---

# Add a tailoring rule

Rule to add: $ARGUMENTS

Before writing code, you need two concrete examples: a rewrite that **must be rejected**, and a similar rewrite of the same facts that **must still pass**. If the request doesn't give both, ask for them.

## Where rules live

- `src/main/java/aijob/tailor/v3/V3EntryRewriteValidator.java`: `validate()` runs the checks in order, and the first failure returns `V3ValidationDecision.reject(code, detail)`.
- `src/main/java/aijob/tailor/v3/V3ValidationDecision.java`: the reason codes.
- `src/test/java/aijob/tailor/v3/V3EntryRewriteValidatorTest.java`: `load()` builds a synthetic resume from `V3CanonicalEntryKeyTest.primeAndSmart()`, with one confirmed and one rejected user answer. `rewrite(...)` and `assertAccepted(...)` are the helpers. For a different resume, build nodes with `V3CanonicalEntryKeyTest.node(id, text)` the way `graphsForCommunication()` does.

BASIC, ENHANCED and `V3HybridSelector` all call this validator, so a new check applies to every mode. Two places are off limits:

- Checks that depend on user-confirmed evidence, which only ENHANCED has. `V3EnhancedBasicFreezeTest` forbids `MISSING_CONFIRMED_EVIDENCE` in the validator.
- The V2 path (`util/Conservative*`), which is frozen.

## Steps

1. **Look for an existing check.** Read `validate()` and `V3ActionObjectProvenanceGate`. If a check nearly covers the case, extend it and explain why, instead of adding a near-duplicate.
2. **Add the reason code** to `V3ValidationDecision`. Use UPPER_SNAKE_CASE and name what was wrong, like `INVENTED_CREDENTIAL`.
3. **Write the tests** in `V3EntryRewriteValidatorTest`:
   - The rewrite that must be rejected: `assertEquals(V3ValidationDecision.NEW_CODE, decision.reasonCode())`.
   - At least one legitimate rewrite of the same facts: `assertAccepted(...)`.

   Keep fixtures synthetic. Run `mvn test "-Dtest=V3EntryRewriteValidatorTest"` and confirm that the rejection test fails because the rewrite is accepted or rejected with a different code.
4. **Implement the check** as a private static method that returns `null` when the rewrite passes and the offending text when it doesn't. Call it from `validate()`:
   - Identity stays first. Put the new check before any broader check that would catch the same rewrite with a less precise code.
   - Compare against the entry's own evidence (`target.originalEntryText()`, `target.factsOf(...)`), never against the job description.
   - Normalize text the way neighboring checks do (`EvidenceText.normalize`, case-insensitive patterns, `Locale.ROOT`).
5. **Run the guard rails**, then the whole suite:

   ```
   mvn test "-Dtest=V3EntryRewriteValidatorTest,V3BasicGoldenTest,V3OfflineQualityFixtureTest,V3EnhancedBasicFreezeTest,TailorV2PathFreezeTest"
   mvn test
   ```

   If the golden or offline-quality tests now reject rewrites they used to accept, the rule is too broad. Narrow the rule; don't change their expectations.
6. **Check other places that read reason codes.** `grep -rn "INVENTED_CREDENTIAL" src frontend/src` shows where codes are handled, such as the live evaluation tests. V3 codes have no UI label today; the tailor page falls back to `tailor.keptOriginalSummary`. If the user wants a label, add it under `reasonCode` in both `en.json` and `zh-CN.json`.
7. **Report** the rule in one sentence, its reason code, where it sits in the order and why, the tests you added, and every command you ran with its result. Suggest a commit message such as `feat: reject <thing> without evidence`.
