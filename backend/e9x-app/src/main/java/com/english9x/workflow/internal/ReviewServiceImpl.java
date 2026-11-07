package com.english9x.workflow.internal;

import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import com.english9x.workflow.api.BundleRef;
import com.english9x.workflow.api.ContentStatus;
import com.english9x.workflow.api.InvalidReviewInputException;
import com.english9x.workflow.api.ReviewQueueEntry;
import com.english9x.workflow.api.ReviewConflictException;
import com.english9x.workflow.api.ReviewNotFoundException;
import com.english9x.workflow.api.ReviewService;
import com.english9x.workflow.api.ReviewView;
import com.english9x.workflow.api.Reviewable;
import com.english9x.workflow.api.ReviewableType;
import com.english9x.workflow.api.SelfReviewException;
import com.english9x.workflow.api.UnpublishedDependencyException;
import com.english9x.workflow.api.VersionRef;
import com.english9x.workflow.api.events.ContentPublished;
import com.english9x.workflow.api.events.ReviewApproved;
import com.english9x.workflow.api.events.ReviewChangesRequested;
import com.english9x.workflow.api.events.ReviewRequested;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The review workflow. One transaction per call; the acting user and organisation are the bound
 * {@link CurrentContextHolder} context, never an argument.
 *
 * <p>Order of work in every decision, and why:
 * <ol>
 *   <li>Read the review as the caller (Row-Level Security: a review outside the caller's context is not there, so it is
 *       "not found" whether or not it exists).
 *   <li>{@link Authz}: REVIEWER of the review's exam in this context (403 otherwise).
 *   <li>Lock the review row(s) {@code FOR UPDATE} and re-read: the loser of a race waits here, then sees a decided review
 *       and answers "conflict". Bundle members are locked in id order.
 *   <li>Lock the content row(s) through {@link ReviewableType#lockForReview}, then the dependencies {@code FOR SHARE}, in a
 *       stable order. Only then are the rules applied (not own version, dependencies PUBLISHED) and the status written, so
 *       what was checked is what is published.
 *   <li>{@code ContentPublished} is published last, after every state change of the call (one per published version).
 * </ol>
 */
@Service
@Transactional
class ReviewServiceImpl implements ReviewService {

    private static final int MAX_COMMENT = 4000;
    private static final Comparator<VersionRef> BY_REF =
            Comparator.comparing(VersionRef::type).thenComparing(VersionRef::versionId);

    private final ReviewRepository repo;
    private final Authz authz;
    private final ApplicationEventPublisher events;
    private final WorkflowProperties props;
    private final Map<String, ReviewableType> types = new HashMap<>();

    ReviewServiceImpl(ReviewRepository repo, Authz authz, ApplicationEventPublisher events, WorkflowProperties props,
                      List<ReviewableType> registered) {
        this.repo = repo;
        this.authz = authz;
        this.events = events;
        this.props = props;
        for (ReviewableType t : registered) {
            if (types.put(t.type(), t) != null) {
                throw new IllegalStateException("two ReviewableType beans for content type '" + t.type() + "'");
            }
        }
    }

    private record Actor(UUID userId, Optional<UUID> orgId) {}

    private static Actor actor() {
        return CurrentContextHolder.current().map(b -> new Actor(b.userId(), b.orgId()))
                .orElseThrow(() -> new AccessDeniedException("Access is denied"));
    }

    private ReviewableType type(String name) {
        ReviewableType t = types.get(name);
        if (t == null) {
            throw new InvalidReviewInputException("type", "unknown", "unknown content type");
        }
        return t;
    }

    // ---- submit ---------------------------------------------------------------------------------------------------------

    private record Submitted(UUID reviewId, VersionRef ref, String examCode, UUID ownerOrgId) {}

    @Override
    public UUID submit(VersionRef version) {
        Actor me = actor();
        Submitted s = submitOne(me, version, null);
        // Audit (P1.9): action review.submit, target = review id, detail = type, versionId, examCode
        events.publishEvent(new ReviewRequested(s.ref().type(), s.ref().versionId(), Optional.ofNullable(s.ownerOrgId()), s.examCode(),
                s.reviewId(), Optional.empty()));
        return s.reviewId();
    }

    @Override
    public UUID submit(BundleRef bundle) {
        Actor me = actor();
        List<VersionRef> members = new ArrayList<>(bundle.members());
        if (new HashSet<>(members).size() != members.size()) {
            throw new InvalidReviewInputException("members", "duplicate", "a version is listed twice in the bundle");
        }
        if (members.size() > props.maxBundleSize()) {
            throw new InvalidReviewInputException("members", "too-many", "a bundle holds at most " + props.maxBundleSize() + " versions");
        }
        members.sort(BY_REF); // content rows are locked in one order, whoever submits
        UUID bundleId = UUID.randomUUID();
        repo.insertBundle(bundleId, me.orgId().orElse(null), me.userId(), members.size());
        Map<String, Submitted> firstPerExam = new LinkedHashMap<>();
        for (VersionRef m : members) {
            Submitted s = submitOne(me, m, bundleId);
            firstPerExam.putIfAbsent(s.examCode(), s);
        }
        // Audit (P1.9): action review.submit-bundle, target = bundle id, detail = member count
        for (Submitted s : firstPerExam.values()) {
            events.publishEvent(new ReviewRequested(s.ref().type(), s.ref().versionId(), Optional.ofNullable(s.ownerOrgId()), s.examCode(),
                    s.reviewId(), Optional.of(bundleId)));
        }
        return bundleId;
    }

    private Submitted submitOne(Actor me, VersionRef ref, UUID bundleId) {
        ReviewableType t = type(ref.type());
        // not in the caller's context = not found, whatever the content module's own visibility rules say
        Reviewable v = t.lockForReview(ref.versionId()).filter(r -> r.ownerOrgId().equals(me.orgId()))
                .orElseThrow(ReviewNotFoundException::new);
        authz.require(Role.AUTHOR, Optional.of(v.examCode()));
        if (!v.status().editable()) {
            throw new ReviewConflictException("only a draft or a version with requested changes can be submitted");
        }
        UUID id = UUID.randomUUID();
        try {
            repo.insertReview(id, bundleId, ref.type(), ref.versionId(), me.orgId().orElse(null), v.examCode(), v.authorId(), me.userId());
        } catch (DuplicateKeyException e) {
            throw new ReviewConflictException("the version is already in review");
        }
        t.setStatus(ref.versionId(), ContentStatus.IN_REVIEW);
        return new Submitted(id, ref, v.examCode(), me.orgId().orElse(null));
    }

    // ---- decisions on one review ---------------------------------------------------------------------------------------------

    @Override
    public void approve(UUID reviewId) {
        Actor me = actor();
        ReviewRepository.Row row = lockForDecision(me, reviewId);
        List<Object> queued = new ArrayList<>();
        Reviewable v = loadForDecision(me, row);
        publish(me, row, v, queued);
        // Audit (P1.9): action review.approve, target = review id, detail = type, versionId, examCode
        queued.forEach(events::publishEvent);
    }

    @Override
    public void requestChanges(UUID reviewId, String comment) {
        String text = comment(comment);
        Actor me = actor();
        ReviewRepository.Row row = lockForDecision(me, reviewId);
        List<Object> queued = new ArrayList<>();
        sendBack(me, row, loadForDecision(me, row), text, queued);
        // Audit (P1.9): action review.request-changes, target = review id, detail = comment length (not the text)
        queued.forEach(events::publishEvent);
    }

    /** Steps 1 to 3 for a single review: visible, REVIEWER of its exam, locked, still open and not part of a bundle. */
    private ReviewRepository.Row lockForDecision(Actor me, UUID reviewId) {
        ReviewRepository.Row seen = repo.find(reviewId).filter(r -> inContext(me, r)).orElseThrow(ReviewNotFoundException::new);
        authz.require(Role.REVIEWER, Optional.of(seen.examCode()));
        ReviewRepository.Row row = repo.lock(reviewId).filter(r -> inContext(me, r)).orElseThrow(ReviewNotFoundException::new);
        if (row.bundleId() != null) {
            throw new ReviewConflictException("the review belongs to a bundle: decide the bundle");
        }
        if (row.status() != ContentStatus.IN_REVIEW) {
            throw new ReviewConflictException("the review is already decided");
        }
        return row;
    }

    private static boolean inContext(Actor me, ReviewRepository.Row r) {
        return Optional.ofNullable(r.ownerOrgId()).equals(me.orgId());
    }

    /** Locks the content row of a review and checks that it is the version under review. */
    private Reviewable loadForDecision(Actor me, ReviewRepository.Row row) {
        Reviewable v = type(row.type()).lockForReview(row.versionId()).filter(r -> r.ownerOrgId().equals(me.orgId()))
                .orElseThrow(() -> new ReviewConflictException("the version no longer exists"));
        if (v.status() != ContentStatus.IN_REVIEW) {
            throw new ReviewConflictException("the version is not in review");
        }
        return v;
    }

    private void requireNotOwn(Actor me, ReviewRepository.Row row, Reviewable v) {
        if (!authz.canReviewOwnVersion()
                && (me.userId().equals(v.authorId()) || me.userId().equals(row.authorId()) || me.userId().equals(row.submittedBy()))) {
            throw new SelfReviewException();
        }
    }

    private void sendBack(Actor me, ReviewRepository.Row row, Reviewable v, String comment, List<Object> queue) {
        requireNotOwn(me, row, v);
        type(row.type()).setStatus(row.versionId(), ContentStatus.CHANGES_REQUESTED);
        decided(repo.decide(row.id(), ContentStatus.CHANGES_REQUESTED, me.userId(), comment));
        queue.add(new ReviewChangesRequested(row.type(), row.versionId(), Optional.ofNullable(row.ownerOrgId()), row.examCode(),
                row.id(), Optional.ofNullable(row.bundleId()), row.authorId()));
    }

    /** Applies the publishing rules to a locked, open review and writes PUBLISHED. Events are queued, not yet published. */
    private void publish(Actor me, ReviewRepository.Row row, Reviewable v, List<Object> queue) {
        requireNotOwn(me, row, v);
        List<VersionRef> missing = new ArrayList<>();
        List<VersionRef> deps = v.dependencies().stream().distinct().sorted(BY_REF).toList(); // stable lock order
        for (VersionRef d : deps) {
            if (!dependencyPublished(me, d)) {
                missing.add(d);
            }
        }
        if (!missing.isEmpty()) {
            throw new UnpublishedDependencyException(missing);
        }
        type(row.type()).setStatus(row.versionId(), ContentStatus.PUBLISHED);
        decided(repo.decide(row.id(), ContentStatus.PUBLISHED, me.userId(), null));
        queue.add(new ContentPublished(row.type(), row.versionId(), Optional.ofNullable(row.ownerOrgId()), row.examCode()));
        queue.add(new ReviewApproved(row.type(), row.versionId(), Optional.ofNullable(row.ownerOrgId()), row.examCode(),
                row.id(), Optional.ofNullable(row.bundleId()), row.authorId()));
    }

    /**
     * Is the dependency PUBLISHED, as far as the caller can know? First the shared lock (nobody changes it until we commit).
     * If the caller cannot lock it, a plain read: accepted only when PUBLISHED and owned by another context (published
     * English9x content used by an organisation; not locked, see {@link ReviewableType#readStatus}). A dependency that
     * does not exist or cannot be read counts as unpublished.
     */
    private boolean dependencyPublished(Actor me, VersionRef d) {
        ReviewableType t = types.get(d.type());
        if (t == null) {
            return false;
        }
        Optional<ContentStatus> locked = t.lockStatus(d.versionId());
        if (locked.isPresent()) {
            return locked.get() == ContentStatus.PUBLISHED;
        }
        return t.readStatus(d.versionId())
                .filter(r -> r.status() == ContentStatus.PUBLISHED && !r.ownerOrgId().equals(me.orgId())).isPresent();
    }

    private static void decided(boolean updated) {
        if (!updated) {
            throw new ReviewConflictException("the review is already decided");
        }
    }

    private static String comment(String comment) {
        if (comment == null || comment.isBlank()) {
            throw new InvalidReviewInputException("comment", "required", "a comment is required");
        }
        if (comment.length() > MAX_COMMENT) {
            throw new InvalidReviewInputException("comment", "too-long", "the comment is too long");
        }
        return comment.strip();
    }

    // ---- decisions on a bundle ---------------------------------------------------------------------------------------------------

    @Override
    public void requestBundleChanges(UUID bundleId, String comment) {
        String text = comment(comment);
        Actor me = actor();
        Map<VersionRef, ReviewRepository.Row> rows = lockBundle(me, bundleId);
        Map<VersionRef, Reviewable> loaded = loadBundle(me, rows);
        List<Object> queued = new ArrayList<>();
        for (Map.Entry<VersionRef, ReviewRepository.Row> e : rows.entrySet()) {
            sendBack(me, e.getValue(), loaded.get(e.getKey()), text, queued);
        }
        // Audit (P1.9): action review.request-bundle-changes, target = bundle id, detail = member count
        onePerAuthor(queued).forEach(events::publishEvent);
    }

    @Override
    public void approveBundle(UUID bundleId) {
        Actor me = actor();
        Map<VersionRef, ReviewRepository.Row> rows = lockBundle(me, bundleId);
        Map<VersionRef, Reviewable> loaded = loadBundle(me, rows);
        List<Object> queued = new ArrayList<>();
        List<VersionRef> missing = new ArrayList<>();
        for (VersionRef ref : dependencyOrder(rows.keySet(), loaded)) {
            try {
                publish(me, rows.get(ref), loaded.get(ref), queued);
            } catch (UnpublishedDependencyException e) {
                // keep going: the reviewer gets every member's missing dependencies at once (nothing is written by a refused member)
                e.missing().stream().filter(d -> !rows.containsKey(d) && !missing.contains(d)).forEach(missing::add);
            }
        }
        if (!missing.isEmpty()) {
            throw new UnpublishedDependencyException(missing); // rolls everything back
        }
        // Audit (P1.9): action review.approve-bundle, target = bundle id, detail = member count
        onePerAuthor(queued).forEach(events::publishEvent);
    }

    /** ContentPublished for every version; ReviewApproved / ReviewChangesRequested once per author (their first member). */
    private static List<Object> onePerAuthor(List<Object> queued) {
        Set<UUID> authors = new HashSet<>();
        List<Object> out = new ArrayList<>();
        for (Object e : queued) {
            UUID author = e instanceof ReviewApproved a ? a.authorId() : e instanceof ReviewChangesRequested c ? c.authorId() : null;
            if (author == null || authors.add(author)) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * The members of a bundle, locked, keyed by version. The caller must be a REVIEWER of every member's exam and see
     * every member: a bundle with a member they cannot decide is refused as a whole.
     */
    private Map<VersionRef, ReviewRepository.Row> lockBundle(Actor me, UUID bundleId) {
        int size = repo.bundleSize(bundleId).orElseThrow(ReviewNotFoundException::new);
        List<ReviewRepository.Row> seen = repo.members(bundleId).stream().filter(r -> inContext(me, r)).toList();
        if (seen.isEmpty()) {
            throw new ReviewNotFoundException();
        }
        Set<String> exams = new HashSet<>();
        for (ReviewRepository.Row r : seen) {
            if (exams.add(r.examCode())) {
                authz.require(Role.REVIEWER, Optional.of(r.examCode()));
            }
        }
        if (seen.size() != size) {
            throw new AccessDeniedException("Access is denied"); // members the caller cannot see, so cannot decide
        }
        List<ReviewRepository.Row> locked = repo.lockMembers(bundleId);
        if (locked.size() != size) {
            throw new AccessDeniedException("Access is denied");
        }
        Map<VersionRef, ReviewRepository.Row> byRef = new LinkedHashMap<>();
        for (ReviewRepository.Row r : locked) {
            if (r.status() != ContentStatus.IN_REVIEW) {
                throw new ReviewConflictException("the bundle is already decided");
            }
            byRef.put(new VersionRef(r.type(), r.versionId()), r);
        }
        return byRef;
    }

    /** Locks the content rows in one stable order and checks each is the version under review. */
    private Map<VersionRef, Reviewable> loadBundle(Actor me, Map<VersionRef, ReviewRepository.Row> rows) {
        Map<VersionRef, Reviewable> loaded = new LinkedHashMap<>();
        for (VersionRef ref : rows.keySet().stream().sorted(BY_REF).toList()) {
            loaded.put(ref, loadForDecision(me, rows.get(ref)));
        }
        return loaded;
    }

    /** Members ordered so each comes after the members it depends on (Kahn). A cycle can never be published. */
    private static List<VersionRef> dependencyOrder(Set<VersionRef> members, Map<VersionRef, Reviewable> loaded) {
        List<VersionRef> order = new ArrayList<>();
        Set<VersionRef> done = new HashSet<>();
        List<VersionRef> remaining = new ArrayList<>(members);
        while (!remaining.isEmpty()) {
            List<VersionRef> ready = remaining.stream()
                    .filter(m -> loaded.get(m).dependencies().stream().filter(members::contains).allMatch(done::contains))
                    .toList();
            if (ready.isEmpty()) {
                throw new ReviewConflictException("the versions of the bundle depend on each other in a circle");
            }
            order.addAll(ready);
            done.addAll(ready);
            remaining.removeAll(ready);
        }
        return order;
    }

    // ---- reading ---------------------------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewView> find(UUID reviewId) {
        return repo.find(reviewId).map(ReviewServiceImpl::view);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewQueueEntry> open(int offset, int limit) {
        return repo.queue(Math.max(0, offset), Math.max(1, Math.min(limit, 200)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewView> members(UUID bundleId, int offset, int limit) {
        return repo.membersPage(bundleId, Math.max(0, offset), Math.max(1, Math.min(limit, 200))).stream().map(ReviewServiceImpl::view).toList();
    }

    private static ReviewView view(ReviewRepository.Row r) {
        return new ReviewView(r.id(), Optional.ofNullable(r.bundleId()), r.type(), r.versionId(), Optional.ofNullable(r.ownerOrgId()),
                r.examCode(), r.authorId(), r.submittedBy(), r.submittedAt(), r.status(), Optional.ofNullable(r.reviewerId()),
                Optional.ofNullable(r.decidedAt()), Optional.ofNullable(r.comment()));
    }
}
