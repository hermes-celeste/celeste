# Code review

This document owns how Celeste requests, evaluates, and responds to human and automated code review.

## Review is advice

A review comment is evidence to evaluate, not an instruction to implement. A finding becomes product work only when it harms an intended Celeste workflow enough to justify the complexity of fixing it. Celeste does not promise perfect behavior for every combination of features, rapid action sequence, concurrent request, or secondary shortcut merely because that path is technically reachable.

Before acting on a finding, establish all of the following:

1. **Intended workflow:** identify the normal user goal being affected. Decide whether this is the primary supported path or an incidental combination of otherwise separate features.
2. **Real-world likelihood:** restate the issue without reviewer severity labels or technical drama. Say who encounters it, while doing what ordinary task, and how often.
3. **Actual consequence:** state what the user loses. Distinguish blocked work, data loss, security, or privacy problems from temporary inconsistency or a shortcut that does not work.
4. **Available path:** determine whether the user can reliably accomplish the same goal through the normal, discoverable workflow. If so, supporting the secondary path must earn its complexity.
5. **Complexity budget:** compare the user value with the added runtime state, synchronization, tests, and maintenance. Reject fixes whose machinery costs more than the problem.

If any item is weak, recommend deferring or rejecting the finding instead of silently widening the PR.

## Triage

Classify every substantive finding before editing:

- **Fix now:** harms an intended workflow, is reasonably likely, has a meaningful consequence, and justifies the complexity of its fix.
- **Hardening:** improves a secondary path and may be useful, but is not required for the current product contract. Implement only when the benefit clearly exceeds the complexity.
- **Follow-up:** valid product or architectural work that needs its own issue or API/design decision.
- **Reject:** does not materially harm an intended workflow, leaves the normal path available, has negligible impact, or requires disproportionate machinery.

Say which class applies and why. “Technically possible” is never sufficient justification for **Fix now**.

## Review workflow

1. Read this document before requesting, performing, or responding to code review.
2. Gather the complete review round before changing code. Evaluate comments together rather than entering a comment-by-comment patch loop.
3. Translate the finding into an ordinary user goal and actual consequence, then reproduce the relevant state transition when practical. Inspect the current implementation and authoritative Hermes behavior before accepting protocol or lifecycle claims.
4. When intended behavior or product treatment is unclear, inspect the current Hermes Desktop implementation as the primary reference. Use it as evidence for what Hermes users actually encounter; do not treat Desktop parity as a Celeste requirement or blindly copy its layout.
5. Present the triage and recommendation to the project owner when feedback would expand product scope, add lifecycle state, introduce compatibility behavior, or materially enlarge the PR.
6. Apply only accepted findings. Add focused regression coverage for behavior that is actually part of Celeste’s supported contract.
7. After a follow-up review, treat findings caused by the previous fix as a signal to step back and reassess the design. Do not keep adding patches solely to make automated review go quiet.
8. Report unresolved limitations honestly. A clean review is not more important than a small, understandable product.

## Automated reviewers

Codex, agents, linters, and other automated reviewers are useful adversarial inputs. They do not know Celeste’s product priorities unless the repository states them, and they tend to optimize for exhaustive technical possibility.

For automated findings:

- translate the claim into an ordinary user goal, supported path, and actual consequence;
- inspect how current Hermes Desktop handles an uncertain case before inventing Celeste-specific machinery;
- reject secondary shortcuts, rapid repetitions, and cross-feature combinations when the normal path remains available and the impact does not justify added complexity;
- reject compatibility suggestions for obsolete Hermes APIs;
- do not manufacture lifecycle machinery for events the product does not promise to survive;
- prefer the smallest product-correct answer, including changing a limit or narrowing a contract;
- never use reviewer approval as the reason for a code change.

Maintain this document when review exposes a reusable decision rule. Keep it a concise policy, not a history of individual PR comments.
