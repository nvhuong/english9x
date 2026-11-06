# English9x — Wave cục bộ (không mạng) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hiện thực và kiểm chứng ngay phần lõi nghiệp vụ thuần Java và schema PostgreSQL/RLS của v1 trong môi trường không mạng, không Docker.

**Architecture:** `backend/e9x-domain` là thư viện Java 21 không phụ thuộc framework; các module Spring sau này gọi vào. Schema là migration Flyway SQL thuần, kiểm chứng bằng PostgreSQL 16 thật qua `scripts/test-db.sh` với role không phải chủ bảng `e9x_app`.

**Tech Stack:** Java 21 (javac), shim JUnit 5 offline, PostgreSQL 16, psql, Node 22.

**Spec:** `docs/superpowers/specs/2026-11-06-english9x-design.md` · Tổng quan: `2026-11-06-english9x-v1-overview.md`

## Global Constraints

- Không thêm thư viện ngoài JDK vào `e9x-domain`. Không import `org.springframework`, `jakarta`.
- Test chỉ dùng: `org.junit.jupiter.api.Test` và `Assertions.assertEquals(Object,Object[,String])`, `assertEquals(long,long)`, `assertEquals(double,double,double)`, `assertTrue/False(boolean[,String])`, `assertThrows`, `assertNull`, `assertNotNull`. Class test đặt tên `*Test`, có constructor không tham số.
- Lệnh kiểm tra: `./scripts/test-domain.sh` (Java) và `./scripts/test-db.sh` (SQL). Biên dịch với `-Xlint:all -Werror`.
- Tiền là `long` VND; thời gian `Instant`; ngày theo `ZoneId` truyền vào. Không đọc đồng hồ hệ thống trong domain (luôn nhận `Instant now`).
- Lỗi đầu vào: `IllegalArgumentException`; chuyển trạng thái sai: `IllegalStateException`.
- Thông số tự đặt nhận qua tham số (không hard-code) — tương ứng khoá cấu hình spec §15.1.
- Mỗi task chỉ sửa file trong thư mục của mình (danh sách **Files**), không sửa script/shim.

## Review Focus

1. Nội dung chuyển khoản có dấu tiếng Việt/ký tự lạ → phải bỏ dấu, in hoa, chỉ `[A-Z0-9 ]`; vượt độ dài → chỉ còn `E9X <mã đơn>`. Test `TransferNoteTest.stripsVietnameseDiacritics`, `fallsBackToOrderCodeWhenTooLong`.
2. Đáp án điền từ vượt giới hạn số từ nhưng có chứa đáp án đúng → tính sai. Test `ObjectiveScorerTest.gapTypedOverWordLimitIsWrong`.
3. IELTS overall ở biên .25/.75 → làm tròn lên. Test `IeltsTest.overallRoundsQuarterUp`, `overallRoundsThreeQuartersUp`.
4. Gói hết hạn giữa giờ thi → vẫn hoàn thành lượt đang làm. Test `AccessPolicyTest.attemptStartedWhileEntitledSurvivesExpiry`.
5. Mở lại bài lần thứ N+1 → tự nộp, gắn cờ gián đoạn, mọi thao tác sau đó bị từ chối trừ đọc trạng thái. Test `ExamAttemptTest.resumeBeyondLimitAutoSubmitsInterrupted`.

---

### Task 1: (L0) Khung repo, harness offline, gate E2E (đã làm bởi điều phối viên)

**Files:** `backend/pom.xml`, `backend/e9x-domain/pom.xml`, `tools/offline-junit/**`, `scripts/test-domain.sh`, `scripts/test-db.sh`, `scripts/check-e2e-coverage.mjs`, `e2e/features.json`, `.gitignore`

- [x] Runner chạy test pass/fail đúng mã thoát (kiểm bằng một test tạm pass + một test tạm fail → exit 1).
- [x] Gate E2E đọc 58 tính năng, báo `0/58`, exit 1.

---

### Task 2: (L1a) Chấm câu khách quan, quy đổi điểm, chính sách chấm (P4.1 + P4.3 phần domain)

**Files:**
- Create: `backend/e9x-domain/src/main/java/com/english9x/domain/scoring/{InteractionKind,Answer,AnswerKey,ItemResult,ItemScorer,ObjectiveScorers,ScoreConverter,TableConverter,Toeic,Ielts}.java`
- Create: `backend/e9x-domain/src/main/java/com/english9x/domain/grading/{GradingMode,GradingPolicyResolver,ScoreRecord,ScoreKind,DisplayedScore,OfficialScore,QueueItemStatus,GradingQueueItem}.java`
- Test: `backend/e9x-domain/src/test/java/com/english9x/domain/scoring/{ObjectiveScorerTest,TableConverterTest,ToeicTest,IeltsTest}.java`, `.../grading/{GradingPolicyResolverTest,OfficialScoreTest,GradingQueueItemTest}.java`

**Interfaces (Produces):**
- `enum InteractionKind { MCQ_SINGLE, MCQ_MULTI, GAP_TYPED, GAP_SELECT, MATCHING, TRUE_FALSE_NG, YES_NO_NG, LABEL_DIAGRAM, SHORT_ANSWER, ORDERING, ESSAY, SPEAKING_RESPONSE; boolean autoScored() }` — `ESSAY`, `SPEAKING_RESPONSE` trả `false`.
- `record Answer(List<String> values)` — `static Answer of(String... v)`, `static Answer empty()`; `values` bất biến, không null.
- `record AnswerKey(List<List<String>> slots, int maxWords, double points)` — mỗi slot là danh sách biến thể chấp nhận; `maxWords = 0` là không giới hạn; `points > 0`; `slots` không rỗng.
- `record ItemResult(double earned, double max, boolean correct)`.
- `interface ItemScorer { ItemResult score(AnswerKey key, Answer answer); }`
- `final class ObjectiveScorers { static ItemScorer forKind(InteractionKind kind) }` — kiểu không tự chấm → `IllegalArgumentException`.
- `interface ScoreConverter { double convert(double raw); }`
- `final class TableConverter implements ScoreConverter { static TableConverter of(Map<Integer, Double> rawToScaled) }`
- `final class Toeic { static int total(int listening, int reading) }`
- `final class Ielts { static double overall(double l, double r, double w, double s); static double writingBand(double task1, double task2); static void requireBand(double b) }`
- `enum GradingMode { AI, TEACHER, BOTH }`; `final class GradingPolicyResolver { static GradingMode resolve(Optional<GradingMode> test, Optional<GradingMode> org, GradingMode exam) }`
- `enum ScoreKind { PRELIMINARY, OFFICIAL }`; `record ScoreRecord(String source, double value, Instant at)` (`source` ∈ `AI`, `TEACHER`); `record DisplayedScore(ScoreRecord score, ScoreKind kind)`; `final class OfficialScore { static Optional<DisplayedScore> select(GradingMode mode, Optional<ScoreRecord> ai, Optional<ScoreRecord> teacher) }`
- `enum QueueItemStatus { OPEN, CLAIMED, DONE }`; `final class GradingQueueItem` với `GradingQueueItem(String id)`, `void claim(String teacherId, Instant now)`, `void complete(String teacherId)`, `boolean releaseIfExpired(Instant now, Duration timeout)`, `void reassign(String teacherId, Instant now)`, getters `status()`, `claimedBy()` (Optional<String>), `claimedAt()` (Optional<Instant>).

**Quy tắc chấm (quyết định của plan):**
- Chuẩn hoá chuỗi: trim, gộp khoảng trắng, chữ thường (Locale.ROOT), bỏ dấu câu cuối `. , ; : ! ?`.
- `MCQ_SINGLE, GAP_SELECT, LABEL_DIAGRAM`: 1 slot, khớp một biến thể sau chuẩn hoá.
- `TRUE_FALSE_NG`: `T|TRUE`→TRUE, `F|FALSE`→FALSE, `NG|NOT GIVEN`→NOT GIVEN; `YES_NO_NG`: `Y|YES`, `N|NO`, `NG|NOT GIVEN`.
- `MCQ_MULTI`: slot 0 là tập đáp án đúng; chọn nhiều hơn số đáp án đúng → 0 điểm; còn lại `earned = points × số đúng / số đáp án đúng`; `correct` khi đủ.
- `MATCHING`: so theo vị trí từng slot, `earned = points × slot đúng / tổng slot`.
- `ORDERING`: đúng toàn bộ thứ tự mới có điểm.
- `GAP_TYPED, SHORT_ANSWER`: từng slot; câu trả lời có số từ > `maxWords` (khi > 0) → slot sai; `earned = points × slot đúng / tổng slot`.
- Câu bỏ trống → 0 điểm, `correct=false`.
- `TableConverter.of`: khoá phải liên tục từ 0 tới max và giá trị không giảm, nếu không → `IllegalArgumentException`; `convert(raw)` nhận raw nguyên trong [0, max], ngoài khoảng → `IllegalArgumentException`.
- `Toeic.total`: mỗi kỹ năng trong [5, 495] và chia hết cho 5.
- `Ielts.overall`: trung bình; phần lẻ < .25 → làm tròn xuống số nguyên; [.25, .75) → .5; ≥ .75 → số nguyên kế. `Ielts.writingBand`: `(t1 + 2·t2)/3`, làm tròn **xuống** tới bội 0.5 (giả định ghi ở spec §3.3, cần xác minh nghiệp vụ). Band hợp lệ: 0–9 bước 0.5.
- `OfficialScore.select`: `AI` → điểm AI là OFFICIAL; `TEACHER` → điểm GV là OFFICIAL; `BOTH` → có GV thì GV OFFICIAL, không thì AI PRELIMINARY; không có điểm phù hợp → empty.
- `GradingQueueItem`: `claim` chỉ khi OPEN; `complete` chỉ người đã nhận, khi CLAIMED; `releaseIfExpired` trả CLAIMED → OPEN khi `now ≥ claimedAt + timeout`; `reassign` chỉ khi OPEN hoặc CLAIMED, chuyển sang CLAIMED cho GV mới với `claimedAt = now`.

**Tests (tên + khẳng định chính):**
- `ObjectiveScorerTest`: `mcqSingleIgnoresCaseAndWhitespace` (key "B", answer " b " → correct); `tfngAcceptsAbbreviations` ("T" vs key "TRUE"); `ynngNotGiven` ("ng" vs "NOT GIVEN"); `mcqMultiPartialCredit` (key {A,C}, answer {A} → 0.5 điểm khi points=1); `mcqMultiTooManyChoicesScoresZero` ({A,B,C} → 0); `matchingPerSlot` (3/4 slot → 0.75); `orderingAllOrNothing`; `gapTypedAcceptsVariants` (key ["colour","color"], "Color." → correct); `gapTypedOverWordLimitIsWrong` (maxWords 2, "the old library" vs key "old library" → wrong); `blankIsWrong`; `essayIsNotAutoScored` (`forKind(ESSAY)` → IllegalArgumentException).
- `TableConverterTest`: `convertsByLookup`; `rejectsGapInKeys`; `rejectsDecreasingValues`; `rejectsRawOutOfRange`.
- `ToeicTest`: `totalSumsSections` (495+495=990, 5+5=10); `rejectsNonMultipleOfFive`; `rejectsOutOfRange`.
- `IeltsTest`: `overallRoundsDown` (6.5,6.5,5.0,7.0 → 6.25→6.5? tính: (6.5+6.5+5+7)/4 = 6.25 → 6.5) đặt tên `overallRoundsQuarterUp`; `overallRoundsThreeQuartersUp` (6.5,6.5,7,7 = 6.75 → 7.0); `overallBelowQuarterRoundsDown` (6,6,6,6.5 = 6.125 → 6.0); `writingBandWeighted` (6.0, 7.0 → 6.667 → 6.5); `rejectsInvalidBand` (9.5, 6.3 → IllegalArgumentException).
- `GradingPolicyResolverTest`: `testOverridesOrgAndExam`; `orgOverridesExam`; `fallsBackToExam`.
- `OfficialScoreTest`: `aiModeAiIsOfficial`; `teacherModeIgnoresAi`; `bothModeAiIsPreliminaryUntilTeacher`; `bothModeTeacherReplacesAi`; `noScoreYieldsEmpty`.
- `GradingQueueItemTest`: `claimThenComplete`; `cannotClaimTwice`; `onlyClaimerCompletes`; `releaseAfterTimeout` (24h); `notReleasedBeforeTimeout`; `reassignByAdmin`.

- [ ] Viết các test trên → `./scripts/test-domain.sh` → FAIL (biên dịch lỗi do chưa có class)
- [ ] Hiện thực theo Interfaces và quy tắc
- [ ] `./scripts/test-domain.sh` → mọi test PASS, exit 0
- [ ] Commit: `feat(domain): objective scoring, score conversion and grading policy`

---

### Task 3: (L1b) VietQR, nội dung chuyển khoản, đơn, gói, quyền học, suất (P5 phần domain)

**Files:**
- Create: `backend/e9x-domain/src/main/java/com/english9x/domain/billing/{Crc16Ccitt,VietQrRequest,VietQr,TransferNote,Codes,OrderStatus,Order,Plan,PlanSelection,EntitlementSource,Entitlement,Entitlements,AccessPolicy,SeatPool}.java`
- Test: `backend/e9x-domain/src/test/java/com/english9x/domain/billing/{Crc16CcittTest,VietQrTest,TransferNoteTest,OrderTest,PlanSelectionTest,EntitlementsTest,AccessPolicyTest,SeatPoolTest}.java`

**Interfaces (Produces):**
- `final class Crc16Ccitt { static int compute(String ascii) }` — CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, không đảo bit, không XOR cuối).
- `record VietQrRequest(String bankBin, String accountNo, long amountVnd, String note)` — `bankBin` đúng 6 chữ số; `accountNo` 1–19 ký tự `[A-Za-z0-9]`; `amountVnd` > 0 và ≤ 9_999_999_999_999; `note` ASCII in được, ≤ 99 ký tự.
- `final class VietQr { static String payload(VietQrRequest r); static Map<String, String> parseTopLevel(String payload) }` — payload EMVCo: `00`=`01`, `01`=`12`, `38`=(`00`=`A000000727`, `01`=(`00`=BIN, `01`=số TK), `02`=`QRIBFTTA`), `53`=`704`, `54`=số tiền, `58`=`VN`, `62`=(`08`=note), `63`=CRC 4 ký tự hex in hoa tính trên toàn chuỗi gồm cả `6304`. Mỗi trường TLV: id 2 ký tự + độ dài 2 chữ số + giá trị. `parseTopLevel` tách TLV cấp 1 (để test và kiểm tra).
- `final class TransferNote { static String build(String orderCode, List<String> courseCodes, int maxLength) }` — `E9X <mã đơn> <mã khoá>…`, in hoa, bỏ dấu (Normalizer NFD + `đ/Đ`→`D`), chỉ giữ `[A-Z0-9 ]`, gộp khoảng trắng; dài hơn `maxLength` → `E9X <mã đơn>`; vẫn dài hơn → `IllegalArgumentException`.
- `final class Codes { static boolean isValidOrderCode(String); static boolean isValidCourseCode(String) }` — đúng 6 ký tự `[A-Z0-9]`.
- `enum OrderStatus { PENDING_PAYMENT, CONFIRMED, REJECTED, EXPIRED }`
- `final class Order` — `Order(String code, String userId, long amountVnd, List<String> courseIds, Instant createdAt)`; `void confirm(Instant now, String bankRef)`; `void reject(Instant now, String reason)` (lý do không rỗng); `boolean expireIfDue(Instant now, Duration ttl)`; getters `status()`, `confirmedAt()` (Optional), `rejectReason()` (Optional), `bankRef()` (Optional). Chỉ PENDING_PAYMENT mới chuyển được.
- `record Plan(String id, long priceVnd, int months, int maxCourses)` — giá > 0, months ≥ 1, maxCourses ≥ 1.
- `final class PlanSelection { static void validate(Plan plan, List<String> courseIds) }` — không rỗng, không trùng, ≤ maxCourses.
- `enum EntitlementSource { ORDER, SEAT }`; `record Entitlement(String userId, String courseId, Instant validFrom, Instant validUntil, EntitlementSource source, String sourceId)` — `validUntil` > `validFrom`.
- `final class Entitlements { static Instant validUntil(Instant confirmedAt, int months, ZoneId zone); static List<Entitlement> forConfirmedOrder(Order order, Plan plan, ZoneId zone) }`
- `final class AccessPolicy { static boolean canStartLearning(List<Entitlement> ents, String userId, String courseId, Instant now); static boolean canContinueAttempt(List<Entitlement> ents, String userId, String courseId, Instant attemptStartedAt) }` — hiệu lực khi `validFrom ≤ t < validUntil`.
- `final class SeatPool` — `SeatPool(int seats)`; `void assign(String userId)`; `void revoke(String userId)`; `int available()`; `boolean isAssigned(String userId)`. Hết suất / đã gán → `IllegalStateException`; thu hồi người chưa gán → `IllegalStateException`.

**Tests:**
- `Crc16CcittTest`: `knownCheckValue` ("123456789" → 0x29B1); `emptyIsInit` ("" → 0xFFFF).
- `VietQrTest`: `buildsExpectedTopLevelFields` (parseTopLevel: 00=01, 01=12, 53=704, 54="150000", 58=VN); `crcMatchesBody` (4 ký tự cuối = `Crc16Ccitt.compute(payload phần trước + "6304")` dạng `%04X`); `merchantInfoContainsBinAndAccount` (trường 38 chứa `A000000727`, BIN `970436`, số TK); `noteInField62`; `rejectsBadBin`; `rejectsNonPositiveAmount`; `rejectsNonAsciiNote`.
- `TransferNoteTest`: `joinsOrderAndCourseCodes` ("E9X D2611A IELW01 TOEL34"); `stripsVietnameseDiacritics` (mã "đề01x" → "DE01X"); `fallsBackToOrderCodeWhenTooLong` (8 khoá, max 50 → "E9X D2611A"); `rejectsWhenEvenOrderCodeTooLong` (max 5).
- `OrderTest`: `confirmFromPending`; `rejectRequiresReason`; `cannotConfirmTwice`; `expiresAfterTtl` (7 ngày); `doesNotExpireBeforeTtl`; `confirmedOrderDoesNotExpire`.
- `PlanSelectionTest`: `acceptsUpToMax`; `rejectsOverMax`; `rejectsDuplicates`; `rejectsEmpty`.
- `EntitlementsTest`: `validUntilAddsCalendarMonths` (31/01 + 1 tháng → 28 hoặc 29/02 theo năm, zone Asia/Ho_Chi_Minh); `confirmedOrderCreatesOnePerCourse`; `pendingOrderCannotCreateEntitlements` (IllegalStateException).
- `AccessPolicyTest`: `activeEntitlementAllows`; `expiredEntitlementDenies`; `otherCourseDenies`; `attemptStartedWhileEntitledSurvivesExpiry`; `attemptStartedAfterExpiryDenied`.
- `SeatPoolTest`: `assignUntilFull`; `revokeFreesSeat`; `cannotAssignTwice`; `cannotRevokeUnassigned`.

- [ ] Viết test → FAIL → hiện thực → PASS (`./scripts/test-domain.sh`) → commit `feat(domain): VietQR payload, transfer note, orders, plans, entitlements, seats`

---

### Task 4: (L1c) Luật mở khoá, kiểm tra chương, chuỗi ngày, thời gian học, vòng đời lượt thi (P3 + P4.5 phần domain)

**Files:**
- Create: `backend/e9x-domain/src/main/java/com/english9x/domain/learning/{UnlockRule,LessonKind,LessonRef,ChapterRef,LessonStatus,UnlockPolicy,ChapterTestAttempts,DailyActivity,Streaks,StudyTime}.java`
- Create: `backend/e9x-domain/src/main/java/com/english9x/domain/exam/{SectionPlan,AttemptStatus,SubmitReason,ResumeOutcome,ExamAttempt}.java`
- Test: `.../learning/{UnlockPolicyTest,ChapterTestAttemptsTest,StreaksTest,StudyTimeTest}.java`, `.../exam/ExamAttemptTest.java`

**Interfaces (Produces):**
- `enum UnlockRule { STRICT, COMPLETION, FREE }`; `enum LessonKind { LESSON, CHAPTER_TEST, MOCK_TEST }`
- `record LessonRef(String id, LessonKind kind)`; `record ChapterRef(String id, List<LessonRef> lessons, double passThreshold)` — ngưỡng trong [0, 1].
- `record LessonStatus(boolean completed, Double bestScore)` — `bestScore` trong [0, 1] hoặc null.
- `final class UnlockPolicy { static boolean isUnlocked(UnlockRule rule, List<ChapterRef> chapters, Map<String, LessonStatus> progress, String lessonId) }` — thứ tự khoá = thứ tự chương rồi thứ tự bài. `FREE`: luôn true. `COMPLETION`: mọi bài trước đó `completed`. `STRICT`: như COMPLETION và mọi `CHAPTER_TEST` trước đó có `bestScore ≥ passThreshold` của chương nó. Bài đầu tiên luôn mở. `lessonId` không có → `IllegalArgumentException`.
- `final class ChapterTestAttempts { static boolean canRetake(int used, int maxAttempts); static Optional<Double> best(List<Double> scores) }` — `maxAttempts = 0` không giới hạn.
- `record DailyActivity(LocalDate date, int minutes, int lessonsCompleted)`
- `final class Streaks { static int current(List<DailyActivity> days, LocalDate today, int minMinutes) }` — ngày đạt khi `minutes ≥ minMinutes` hoặc `lessonsCompleted ≥ 1`; đếm chuỗi ngày liên tiếp kết thúc tại `today` nếu hôm nay đạt, nếu chưa thì kết thúc tại hôm qua; không đạt → 0.
- `final class StudyTime { static Duration credited(List<Instant> heartbeats, Duration idleTimeout) }` — sắp xếp; cộng khoảng cách giữa hai tín hiệu liên tiếp khi khoảng cách ≤ `idleTimeout`; tín hiệu đầu không cộng.
- `record SectionPlan(String id, Duration duration)`; `enum AttemptStatus { IN_PROGRESS, SUBMITTED }`; `enum SubmitReason { LEARNER, TIME_UP, RESUME_LIMIT }`; `enum ResumeOutcome { CONTINUED, AUTO_SUBMITTED }`
- `final class ExamAttempt`:
  - `static ExamAttempt start(String id, List<SectionPlan> sections, int maxResumes, Instant now)`
  - `Optional<SectionPlan> currentSection(Instant now)` — tự chuyển phần khi hết giờ phần; hết giờ phần cuối → tự nộp `TIME_UP` và trả empty.
  - `Instant currentSectionEndsAt(Instant now)` — lỗi `IllegalStateException` khi đã nộp.
  - `void finishSectionEarly(Instant now)` — sang phần sau ngay; là phần cuối → nộp `LEARNER`.
  - `boolean canAnswer(String sectionId, Instant now)` — chỉ phần hiện tại, trước khi hết giờ, khi chưa nộp.
  - `ResumeOutcome resume(Instant now)` — tăng `resumeCount`; vượt `maxResumes` → nộp `RESUME_LIMIT`, `interrupted = true`; đã nộp → `IllegalStateException`.
  - `boolean submit(Instant now, SubmitReason reason)` — lần đầu trả true; đã nộp trả false, không đổi lý do/thời điểm.
  - Getters: `status()`, `resumeCount()`, `interrupted()`, `submitReason()` (Optional), `submittedAt()` (Optional).

**Tests:**
- `UnlockPolicyTest`: `freeUnlocksEverything`; `firstLessonAlwaysUnlocked`; `completionRequiresPreviousCompleted`; `completionIgnoresScores` (kiểm tra chương 0.2 vẫn mở chương sau); `strictRequiresChapterTestPass` (0.6 < 0.7 → khoá; 0.7 → mở); `unknownLessonRejected`.
- `ChapterTestAttemptsTest`: `unlimitedWhenZero`; `limitedRetakes`; `bestOfScores`; `bestOfEmptyIsEmpty`.
- `StreaksTest`: `countsConsecutiveDaysEndingToday`; `todayNotYetQualifiedCountsFromYesterday`; `gapBreaksStreak`; `lessonCompletionQualifiesShortDay`; `noActivityIsZero`.
- `StudyTimeTest`: `sumsGapsWithinIdle` (beats 0s,30s,60s → 60s); `ignoresGapsBeyondIdle` (0s,30s,10m,10m30s, idle 2m → 60s); `unsortedInput`; `singleBeatIsZero`.
- `ExamAttemptTest`: `startsInFirstSection`; `autoAdvancesWhenSectionTimeUp`; `autoSubmitsWhenLastSectionTimeUp` (lý do TIME_UP); `finishEarlyMovesToNextSection`; `cannotAnswerPreviousSection`; `resumeWithinLimitContinues`; `resumeBeyondLimitAutoSubmitsInterrupted` (max 2: lần 3 → AUTO_SUBMITTED, interrupted); `submitIsIdempotent` (lần 2 trả false, lý do giữ nguyên); `resumeAfterSubmitRejected`.

- [ ] Viết test → FAIL → hiện thực → PASS → commit `feat(domain): unlock rules, streaks, study time and exam attempt lifecycle`

---

### Task 5: (L1d) Schema nền + RLS + test PostgreSQL (P1.3 phần SQL)

**Files:**
- Create: `backend/e9x-app/src/main/resources/db/migration/V202611060001__org_baseline.sql`
- Create: `backend/db-tests/01_isolation.sql`, `02_attempt_single_in_progress.sql`, `03_entitlement_source.sql`, `04_role_grant_unique.sql`, `05_answer_key_column_privilege.sql`

**Schema (Produces):**
- Role `e9x_app` (NOLOGIN, tạo nếu chưa có); hàm `e9x_current_org() returns uuid` và `e9x_current_user() returns uuid` đọc `current_setting('app.org_id', true)` / `'app.user_id'` (rỗng → NULL), `STABLE`.
- Bảng: `users(id uuid pk default gen_random_uuid(), email text not null, display_name text, created_at timestamptz default now())` + unique index `lower(email)`; `organizations(id, name, type check in ('CENTER','ENTERPRISE'), created_at)`; `org_memberships(org_id fk, user_id fk, pk(org_id,user_id))`; `role_grants(id, user_id fk, org_id fk null, exam_code text null, role check in ('LEARNER','AUTHOR','REVIEWER','TEACHER','ORG_ADMIN','SYS_ADMIN'), unique nulls not distinct (user_id, org_id, exam_code, role))`; `exams(code pk, name)`; `courses(id, owner_org_id null fk, exam_code fk, code char(6) unique check ~ '^[A-Z0-9]{6}$', title, status check in ('DRAFT','IN_REVIEW','CHANGES_REQUESTED','PUBLISHED','ARCHIVED'), shared_status check in ('NONE','PENDING','APPROVED') default 'NONE')`; `questions(id, owner_org_id null, exam_code, content jsonb not null, answer_key jsonb not null, status)`; `entitlements(id, user_id fk, course_id fk, valid_from, valid_until, source_order_id uuid null, source_seat_id uuid null, check valid_until > valid_from, check num_nonnulls(source_order_id, source_seat_id) = 1)`; `exam_attempts(id, user_id fk, owner_org_id null, test_version_id uuid not null, status check in ('IN_PROGRESS','SUBMITTED'), started_at, submitted_at)` + unique index `(user_id, test_version_id) where status = 'IN_PROGRESS'`; `classes(id, owner_org_id not null fk, name)`.
- RLS bật cho `organizations, courses, questions, entitlements, exam_attempts, classes`:
  - `courses` SELECT: `(owner_org_id is null and status='PUBLISHED') or owner_org_id = e9x_current_org() or (shared_status='APPROVED' and status='PUBLISHED')`; INSERT/UPDATE/DELETE: `owner_org_id is not distinct from e9x_current_org()`.
  - `questions`: SELECT/INSERT/UPDATE/DELETE: `owner_org_id is not distinct from e9x_current_org()` (học viên không đọc trực tiếp bảng câu hỏi; phòng thi đọc qua view ở P3.3).
  - `classes`: mọi thao tác: `owner_org_id = e9x_current_org()`.
  - `exam_attempts` SELECT: `user_id = e9x_current_user() or (owner_org_id is not null and owner_org_id = e9x_current_org())`; INSERT/UPDATE: `user_id = e9x_current_user()`.
  - `entitlements` SELECT: `user_id = e9x_current_user()`.
  - `organizations` SELECT: `id = e9x_current_org() or exists (membership của e9x_current_user())`.
- Quyền: `GRANT SELECT, INSERT, UPDATE, DELETE` trên mọi bảng cho `e9x_app`, **trừ** cột `questions.answer_key`: `e9x_app` chỉ được SELECT các cột `id, owner_org_id, exam_code, content, status` (quyền đọc `answer_key` cấp cho role `e9x_grader` riêng dùng bởi module chấm).

**Tests** (mỗi file: dựng dữ liệu bằng superuser, `SET ROLE e9x_app`, đặt `app.org_id`/`app.user_id` bằng `set_config`, khẳng định bằng `DO $$ … ASSERT … $$` hoặc bắt lỗi mong đợi trong khối `BEGIN … EXCEPTION`):
- `01_isolation.sql`: ngữ cảnh tổ chức A thấy lớp của A, không thấy lớp của B; thấy khoá English9x PUBLISHED, không thấy khoá English9x DRAFT; thấy khoá của B đã APPROVED + PUBLISHED, không thấy khoá DRAFT của B; INSERT lớp với `owner_org_id = B` → lỗi RLS (`insufficient_privilege` / `new row violates row-level security policy`); không có ngữ cảnh → không thấy lớp nào.
- `02_attempt_single_in_progress.sql`: lượt IN_PROGRESS thứ hai cùng người + đề → `unique_violation`; sau khi lượt đầu SUBMITTED thì tạo được lượt mới; người khác không đọc được lượt của người này.
- `03_entitlement_source.sql`: cả hai nguồn null → `check_violation`; cả hai có → `check_violation`; `valid_until ≤ valid_from` → `check_violation`; học viên chỉ thấy entitlement của mình.
- `04_role_grant_unique.sql`: hai dòng `(u, NULL, NULL, 'SYS_ADMIN')` → `unique_violation`.
- `05_answer_key_column_privilege.sql`: `SET ROLE e9x_app; SELECT answer_key FROM questions` → `insufficient_privilege`; `SELECT content` thành công.

- [ ] Viết 5 file test → `./scripts/test-db.sh` → FAIL (chưa có migration)
- [ ] Viết migration → `./scripts/test-db.sh` → 5/5 PASS
- [ ] Commit `feat(db): org baseline schema with row-level security and isolation tests`

---

### Task 6: (L2) Review toàn nhánh (reviewer độc lập)

- [ ] Reviewer chưa tham gia L1a–L1d đọc spec + plan + diff, chạy `./scripts/test-domain.sh`, `./scripts/test-db.sh`, `node scripts/check-e2e-coverage.mjs`, báo lỗi theo mức độ.
- [ ] Sửa các lỗi Critical/Important, chạy lại toàn bộ.
