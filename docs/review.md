# Code review

This document owns how Celeste requests, evaluates, and responds to human and automated code review.

## Review is advice

A review comment is evidence to evaluate, not an instruction to implement. Do not change code merely because a reviewer can construct a technically possible failure or because another review is needed to obtain approval.

Before acting on a finding, establish all of the following:

1. **Reachability:** identify a concrete path through the current app using supported devices, configurations, and the current Hermes API.
2. **Plausibility:** explain why a real Celeste user is reasonably likely to encounter it. A hypothetical race, future feature, obsolete server, unusual device, or hostile external mutation is not enough by itself.
3. **Impact:** state what the user loses when it occurs. Prefer observable correctness, security, privacy, data loss, and blocked workflows over internal symmetry.
4. **Scope:** confirm that the behavior belongs to the PR’s acceptance criteria or is a regression caused directly by the change.
5. **Proportionality:** compare the risk with the runtime state, branches, tests, and maintenance cost required to handle it. Prefer a simpler product constraint when it removes the scenario cleanly.

If any item is weak, recommend deferring or rejecting the finding instead of silently widening the PR.

## Triage

Classify every substantive finding before editing:

- **Fix now:** reachable, plausible, meaningful, and in scope.
- **Hardening:** plausible and useful, but not required for the current PR. Implement only when the benefit clearly exceeds the complexity.
- **Follow-up:** valid product or architectural work that needs its own issue or API/design decision.
- **Reject:** theoretical only, based on unsupported behavior, obsolete compatibility, negligible impact, or disproportionate machinery.

Say which class applies and why. “Technically possible” is never sufficient justification for **Fix now**.

## Review workflow

1. Read this document before requesting, performing, or responding to code review.
2. Gather the complete review round before changing code. Evaluate comments together rather than entering a comment-by-comment patch loop.
3. Reproduce or demonstrate a concrete state transition when practical. Inspect the current implementation and authoritative Hermes behavior before accepting protocol or lifecycle claims.
4. When plausibility, expected behavior, or product treatment is unclear, inspect the current Hermes Desktop implementation as the primary reference. Use it as evidence for what Hermes users actually encounter; do not blindly copy desktop layout into the mobile client.
5. Present the triage and recommendation to the project owner when feedback would expand product scope, add lifecycle state, introduce compatibility behavior, or materially enlarge the PR.
6. Apply only accepted findings. Add focused regression coverage for behavior that is actually part of Celeste’s supported contract.
7. After a follow-up review, treat findings caused by the previous fix as a signal to step back and reassess the design. Do not keep adding patches solely to make automated review go quiet.
8. Report unresolved limitations honestly. A clean review is not more important than a small, understandable product.

## Automated reviewers

Codex, agents, linters, and other automated reviewers are useful adversarial inputs. They do not know Celeste’s product priorities unless the repository states them, and they tend to optimize for exhaustive technical possibility.

For automated findings:

- verify claims against current source and supported behavior;
- inspect how current Hermes Desktop handles an uncertain case before inventing Celeste-specific machinery;
- discount scenarios that require implausible timing or unsupported environments;
- reject compatibility suggestions for obsolete Hermes APIs;
- do not manufacture lifecycle machinery for events the product does not promise to survive;
- prefer the smallest product-correct answer, including changing a limit or narrowing a contract;
- never use reviewer approval as the reason for a code change.

Maintain this document when review exposes a reusable decision rule. Keep it a concise policy, not a history of individual PR comments.
