# Hợp đồng domain event (P1.11)

Nguồn: spec §5.3, §10.4, §11, §12 và plan P1.11. Hình dạng bản ghi **cố định**: đổi field phải đổi plan và mọi consumer.

## Quy ước chung

- Mỗi event là một `record` trong `com.english9x.<module>.api.events`, một file một record. Gói đó là named interface `events` của module phát hành (`package-info.java` với `@NamedInterface("events")`), nên module khác chỉ cần phụ thuộc vào `<module>::events`, không chạm `internal`. `ModuleEventsTest` kiểm tra phía phát hành (mọi event nằm trong named interface `events` của module phát hành). Phía consumer sẽ được `ModularityTest` kiểm tra khi P4.1 thêm listener thật đầu tiên (Modulith không đưa test class vào mô hình module).
- Phát bằng `ApplicationEventPublisher` **trong transaction** của nghiệp vụ gây ra nó. Spring Modulith ghi event vào bảng publication (event publication registry) cùng transaction; listener chạy **sau commit**.
- Nghe bằng `@ApplicationModuleListener` (bất đồng bộ, transaction riêng, sau commit). Giao **ít nhất một lần**: listener chưa hoàn tất khi tiến trình dừng sẽ được phát lại, nên **mọi consumer phải idempotent** (khoá tự nhiên là id trong event).
- Thứ tự giữa các event khác nhau không được bảo đảm. Event không mang bí mật, đáp án hay dữ liệu cá nhân ngoài id; consumer đọc dữ liệu bằng API của module phát hành.
- Thời gian là `Instant` UTC. `Optional<UUID> orgId` rỗng = ngữ cảnh English9x/cá nhân.
- Consumer đặt ngữ cảnh RLS của mình (`app.org_id`) từ `orgId` của event khi cần; không giả định luồng request.

## Danh sách

| Event | Module phát hành | Khi nào phát | Consumer dự kiến |
|---|---|---|---|
| `AttemptSubmitted(attemptId, userId, testVersionId, Optional<UUID> orgId, Instant submittedAt, String reason, boolean interrupted)` | `exam` | Lượt thi được nộp (người dùng bấm nộp hoặc job `auto_submit_attempt` khi hết giờ). Idempotent: nộp lại không phát lần hai. `reason` nêu nguyên nhân (người dùng / hết giờ / gián đoạn quá số lần tiếp tục); `interrupted` đánh dấu bài gián đoạn (nhãn cảnh báo ở kết quả). | `grading` (chấm tự động, xếp hàng chấm W/S), `learning` (thời gian học) |
| `AttemptScored(attemptId, userId, Map<String,Double> skillScores, boolean official)` | `grading` | Khi đủ điểm theo chính sách chấm (§10.4): điểm sơ bộ tự động, và lần nữa khi có điểm chính thức. `official=false` là nhãn "sơ bộ". Khoá của map là mã kỹ năng (ví dụ `L`, `R`, `W`, `S`). | `analytics` (thống kê theo dạng câu), `learning` (tiến độ, chuỗi ngày), `notification` (báo có kết quả) |
| `ContentPublished(String type, UUID versionId, Optional<UUID> ownerOrgId, String examCode)` | `workflow` | Người duyệt phê duyệt (`ReviewService.approve`) và phiên bản chuyển PUBLISHED. `type` là loại nội dung (câu hỏi, nhóm câu, đề, bài phương pháp, khoá học ...); `ownerOrgId` rỗng = nội dung English9x. Phiên bản đã xuất bản bất biến; phát đúng một lần cho mỗi phiên bản. | `catalog` / `course` (làm mới danh mục), `notification` |
| `ReviewRequested(String type, UUID versionId, Optional<UUID> ownerOrgId, String examCode, UUID reviewId, Optional<UUID> bundleId)` | `workflow` | Gửi duyệt xong (`ReviewService.submit`). Với cụm: một event cho mỗi kỳ thi trong cụm (đại diện là phần tử đầu của kỳ thi đó), `bundleId` có giá trị. Spec §13 "nội dung chờ duyệt". | `notification` (REVIEWER của kỳ thi, trong ngữ cảnh của nội dung) |
| `ReviewChangesRequested(String type, UUID versionId, Optional<UUID> ownerOrgId, String examCode, UUID reviewId, Optional<UUID> bundleId, UUID authorId)` | `workflow` | Người duyệt yêu cầu sửa. Với cụm: một event cho mỗi tác giả (phần tử đầu của tác giả đó). Nhận xét không nằm trong event (đọc bằng `ReviewService.find`). | `notification` (tác giả) |
| `ReviewApproved(String type, UUID versionId, Optional<UUID> ownerOrgId, String examCode, UUID reviewId, Optional<UUID> bundleId, UUID authorId)` | `workflow` | Người duyệt phê duyệt; phát cùng `ContentPublished`. Với cụm: một event cho mỗi tác giả. | `notification` (tác giả) |
| `OrderConfirmed(orderId, userId, List<UUID> courseIds, Instant confirmedAt)` | `billing` | Quản trị xác nhận đơn (§11), cùng transaction với việc tạo entitlement; hạn tính từ `confirmedAt`. | `notification` (báo học viên), `learning` (mở quyền học) |
| `OrderRejected(orderId, String reason)` | `billing` | Quản trị từ chối đơn; `reason` do quản trị nhập. | `notification` |
| `SeatAssigned(licenseId, userId, List<UUID> courseIds)` | `billing` | `ORG_ADMIN` gán suất cho người dùng với các khoá trong danh sách được phép (§12); entitlement nguồn tổ chức đã tạo. | `notification`, `learning` |
| `SeatRevoked(licenseId, userId)` | `billing` | Thu hồi suất: entitlement kết thúc ngay, suất trở lại kho. | `notification`, `learning` |
| `UserRegistered(userId, email, occurredAt)` | `identity` | Tài khoản mới có đồng ý xử lý dữ liệu: đăng ký email + mật khẩu (trong transaction đăng ký), hoặc tài khoản Google khi chủ tài khoản đồng ý lần đầu (không phải lúc tạo dòng `users`; tài khoản bị vô hiệu hoá mật khẩu do pre-hijack không phát lại). Không mang token xác thực (token chỉ gửi qua email cho chủ hộp thư). Lần đăng ký trùng email không phát event. (P1.4) | `notification` (chào mừng, tuỳ chọn), `analytics` |

"Consumer dự kiến" theo plan các dự án con P2-P6; module thật sự đăng ký listener khi đến task tương ứng.

## Giao ít nhất một lần: đã nối (P1.8)

Từ P1.8 Spring Modulith ghi bảng `event_publication` (migration `V202611080004__event_publication.sql`, `spring-modulith-starter-jdbc`) trong transaction của bên phát; mỗi listener `@ApplicationModuleListener` có một dòng cho đến khi nó chạy xong (`spring.modulith.events.completion-mode=delete`: xong là xoá, không lưu lâu dữ liệu). Event chưa xong được gửi lại khi khởi động (`republish-outstanding-events-on-restart`) và định kỳ khi đủ tuổi `e9x.events.redelivery-after` (`platform.events.EventRedelivery`). Hệ quả cho người viết consumer:
- **Idempotent bằng khoá tự nhiên.** `notification` dùng `(userId, "<loại>:<reviewId>")` làm khoá chống trùng trong `NotificationService.notify`.
- Listener chạy trên luồng async **không có ngữ cảnh người dùng** (`platform.events.AsyncContextCleaner` xoá/khôi phục binding ở đầu và cuối mỗi tác vụ). Cần RLS của một người thì tự `CurrentContextHolder.bind` TRƯỚC khi mở transaction.
- Bean có listener giao dịch bị Modulith bọc proxy (CGLIB): không đọc field `public` của bean đó từ ngoài, dùng phương thức.
- Event còn dòng chưa xong chứa dữ liệu event (id; `UserRegistered` có email) đến khi listener hoàn tất: giữ event nhỏ, không bí mật.

## Lỗi API (liên quan)

Mọi phản hồi lỗi là `application/problem+json`: `type` (`urn:e9x:problem:<slug>`), `title`, `status`, `detail`, `errors[]` (luôn có; mỗi phần tử `field`, `row`, `code`, `message`). `ProblemDetailsAdvice` ở `com.english9x.platform.web`.
