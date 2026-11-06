# SDD ledger — plan: docs/superpowers/plans/2026-11-06-english9x-local-wave.md
Spec: docs/superpowers/specs/2026-11-06-english9x-design.md
BASE (release/v1): 53a3823
Task 1: complete (L0 by controller, commit 6c00d9d)

## Pre-flight scan
| Pair / task | Shared file or interface | Finding |
|---|---|---|
| T2 vs T4 | both add to backend/e9x-domain, different packages (scoring/grading vs learning/exam) | no shared file; no interface consumed |
| T2 vs T3 | none | independent |
| T3 vs T4 | AccessPolicy (T3) and ExamAttempt (T4) both about attempts | no code dependency; T3 takes attemptStartedAt as plain Instant |
| T5 vs T2-T4 | SQL only | independent |
| T2 self | IeltsTest bullet names test overallRoundsDown then says "đặt tên overallRoundsQuarterUp" | Ruling: test name overallRoundsQuarterUp, data 6.5/6.5/5.0/7.0 -> 6.5 — wording slip — cost: none |
| T3 self | EntitlementsTest "28 hoặc 29/02" ambiguous | Ruling: use 2027-01-31T10:00 Asia/Ho_Chi_Minh + 1 month -> 2027-02-28T10:00 local — deterministic year — cost: none |
| T4 self | consistent | — |
| T5 self | questions write policy uses IS NOT DISTINCT FROM (null ctx = English9x content) | consistent with spec §6 principle 1 |
| Process | skill says never dispatch implementers in parallel | Ruling: dispatch T2–T5 in parallel, one git worktree each — user explicitly requested subagents + worktrees + parallel; files are disjoint — cost if wrong: merge conflicts in release/v1 |
Task 3: implementer DONE f1b04bb (35/35)
Task 2: implementer DONE_WITH_CONCERNS e119042 (38/38) — writingBand assumption; open choices listed in report
Task 4: implementer DONE 5a1a06a (28/28)
Task 5: implementer DONE 897a3f0 (5/5 db tests; mutation-checked)
Task 3: review Approved (spec ✅)
Task 3: minor (deferred): VietQrRequest note limit 99 but effective 95 (field 62 wrap); unreachable with 50-char notes
Task 3: minor (deferred): crcMatchesBody tautological; add golden VietQR payload vector + amount upper-bound test
Task 3: minor (deferred): Order.reject ignores now; no null checks on now; empty courseIds accepted
Task 3: minor (deferred): NPE instead of IAE on nulls in Entitlement/TransferNote
Task 3: minor (deferred): parseTopLevel accepts "+5" length; no leap-year entitlement test; forConfirmedOrder does not cross-check plan
Task 3: complete (commits 53a3823..f1b04bb, review clean)
Task 2: review Approved (spec ✅)
Task 2: minor (deferred): normalization ignores non-breaking/Unicode whitespace ("old library" fails) — relevant to Review Focus #4, final review should triage
Task 2: minor (deferred): AnswerKey accepts blank variants (MCQ_MULTI full credit impossible), NPE on null variants; TableConverter NPE/infinite values; extra key slots ignored; ScoreRecord accepts negative/infinite
Task 2: minor (deferred): test gaps — SHORT_ANSWER, multi-slot gap partial, out-of-vocab TFNG, inner whitespace, exact-half averages, writing floor edges
Task 2: complete (commits 53a3823..e119042, review clean)
Task 4: review Needs fixes (spec ❌): resume/submit ignore time-up; missing boundary/long-gap tests
Task 4: minor (deferred): stale getters until sync; duplicate SectionPlan ids not rejected; STRICT null-score & later-chapter tests; NPE on null list elements; extra validation untested; minMinutes=0 doc
Task 4: fix round 1 implementer done ec6e71e (34/34)
Task 5: review Approved with 3 Important plan-mandated design gaps
Task 5: Ruling: null org context must not grant write on English9x rows (or read of English9x questions) — add GUC app.e9x_staff ('on' only set by server for English9x staff); owner-null writes and null-context question reads require it — spec §6 says only published English9x content is readable; brief predicate too loose — cost if wrong: app must set the GUC for staff sessions (P1.3 RlsSessionConfigurer)
Task 5: Ruling: org_memberships/role_grants writes require app.e9x_staff='on' or (app.org_admin='on' and org_id = current org); SELECT limited to own rows or current org; users RLS deferred to P1.4 — membership table is the trust root for organizations_select — cost if wrong: invite/admin code paths must set GUCs
Task 5: Ruling: shared_status may become APPROVED only when app.e9x_staff='on' (trigger) — spec §7.1 kho chung requires English9x review — cost if wrong: none
Task 5: minor (deferred): untested cross-tenant UPDATE/DELETE, org-A-cannot-see-B questions, exam_attempts insert for other user; exam_attempts_insert does not constrain owner_org_id; dead DML grants uncommented; constraint_name not asserted; wrap helpers in (SELECT ...)
Task 4: fix round 1/5 (3 addressed, 0 open; commits 5a1a06a..ec6e71e)
Task 4: minor (deferred): no exact-deadline-instant test for submit/resume
Task 4: complete (commits 53a3823..ec6e71e, review clean)
Task 5: fix round 1 implementer done f36c4ae (8/8)
Task 5: fix round 1/5 (3 addressed, 1 new open — staff cannot SELECT non-PUBLISHED English9x courses, blocking update/delete/publish; commits 897a3f0..f36c4ae)
Task 5: minor (deferred): GUCs app.e9x_staff/app.org_admin are session-settable by e9x_app (trusted-setter model; document in P1.3); organizations_select trusts current org GUC; trigger applies to superuser fixtures
Task 5: fix round 2/5 (1 addressed, 0 open; commits f36c4ae..041523b)
Task 5: minor (deferred): test 09 lacks non-staff UPDATE/DELETE 0-row assertion on drafts
Task 5: complete (commits 53a3823..041523b, review clean)
Final review: With fixes — 0 Critical, 9 Important (+ fix-now minors)
Ruling: OfficialScore — a teacher score, when present, is OFFICIAL in every mode (spec §10.3 "điểm giáo viên là chính thức"; AI-failure hand-off) — cost if wrong: AI-mode tests change back
Ruling: TableConverter accepts non-integer raw and floors with 1e-9 tolerance (partial credit never rounds up) — spec silent; IELTS/TOEIC count whole marks — cost if wrong: one-band differences on partial-credit items
Ruling: single-answer kinds and MCQ_MULTI require exactly one key slot; validated by ObjectiveScorers (IAE) — prevents silent mis-scoring — cost if wrong: authors must split multi-blank GAP_SELECT into MATCHING
Ruling: late offline answers — ExamAttempt.acceptsAnswer(sectionId, answeredAt, now, grace): answeredAt within that section's window, and now < sectionEnd + grace; grace = new config e9x.exam.late-sync-grace (default 2m) — spec §9 says offline answers resync; client time capped by section window — cost if wrong: answers synced after grace lost
Ruling: fix-now set = Important 1–9 + Unicode/apostrophe normalization + blank/null variants + order-code validation in TransferNote + RLS-enabled-on-all-tables test + cross-tenant UPDATE/DELETE tests + gate ignores commented tests & checks manifest count; everything else per reviewer's "can wait"/"P1.3" triage stays deferred
Final fix wave: implementer DONE_WITH_CONCERNS e31e553..476ebcf (domain 129/129, db 14/14, gate selftest 13/13)
Final fix wave: re-review — all 15 addressed, no new Critical/Important
Final: parked — staff cannot withdraw/reject a shared course back to NONE — Ruling: deferred to P6.5 (add REJECTED status or definer function) — real, nothing in this wave builds on it
Final: parked — e9x_grader reads PUBLISHED answer keys of any org — Ruling: deferred; narrow to (owner NULL and PUBLISHED) or current org in P4.1 — trusted backend role, low risk
Final: parked — RLS-all-tables test allowlist lacks flyway_schema_history — Ruling: P1.3 adds it to allowlist when Flyway runs
Final: parked — ENTERPRISE role rule not rechecked on org type change — Ruling: P1.5 forbids changing org type when incompatible grants exist
Final: minor (deferred): gate comment stripper ignores regex literals; duplicate null-slot check in AnswerKey; courses_staff_review allows any column update
