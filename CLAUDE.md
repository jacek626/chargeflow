# ChargeFlow — CLAUDE.md

## Project

ChargeFlow is an Event-Driven EV Charging Platform on AWS.

Current target: **V1**

## Source of truth

Before implementing business functionality, read:

- `docs/product-specs/chargeflow-spec.md`
- `docs/exec-plans/backlog.md`
- `ARCHITECTURE.md`

Do not invent requirements not present in project documentation.

## V1 architecture

V1 contains exactly:

- Charging Session Service
- Notification Service
- Angular frontend

Do NOT implement yet:

- Station Service
- Payment Service
- Analytics Service
- Kafka/MSK
- Kubernetes/EKS
- ML
- Bedrock
- RAG
- AI agents

unless an active task explicitly requires it.

## Development rules

- Work on one task at a time.
- Every task must have an ID such as `CF-001`.
- Read task dependencies before coding.
- Inspect existing code before modifying it.
- Do not modify public REST or event contracts without an explicit requirement.
- Do not add frameworks or AWS services without an explicit requirement.
- Do not implement future-version functionality early.
- Prefer simple implementations over speculative abstractions.

## Completion rules

After implementing a task:

1. Run appropriate tests.
2. Verify every acceptance criterion.
3. Update task status only if all criteria pass.
4. Update CHANGELOG.
5. Summarize modified files.
