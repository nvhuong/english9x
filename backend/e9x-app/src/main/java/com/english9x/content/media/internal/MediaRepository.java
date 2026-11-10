package com.english9x.content.media.internal;

import com.english9x.content.media.api.MediaAssets;
import com.english9x.content.media.api.MediaKind;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** media_assets, always in the caller's transaction: row-level security (author / reviewer of the exam, owner's context) is the access rule. */
@Repository
class MediaRepository implements MediaAssets {

    private final JdbcTemplate jdbc;

    MediaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MediaInfo> find(UUID mediaId) {
        return jdbc.query("""
                select id, owner_org_id, exam_code, kind, mime_type, size_bytes, duration_ms, original_filename
                  from media_assets where id = ?
                """, (rs, n) -> new MediaInfo(rs.getObject(1, UUID.class), Optional.ofNullable(rs.getObject(2, UUID.class)), rs.getString(3),
                MediaKind.valueOf(rs.getString(4)), rs.getString(5), rs.getLong(6),
                rs.getObject(7) == null ? Optional.empty() : Optional.of(rs.getLong(7)), rs.getString(8)), mediaId).stream().findFirst();
    }

    /** The INSERT policy asks the database for the same thing Authz asked: the author of the exam, in the owner's context, as themselves. */
    @Transactional
    void insert(UUID id, UUID ownerOrgId, String examCode, MediaKind kind, String mimeType, long sizeBytes, Long durationMs, String filename, UUID uploadedBy) {
        jdbc.update("""
                insert into media_assets(id, owner_org_id, exam_code, kind, mime_type, size_bytes, duration_ms, original_filename, uploaded_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, ownerOrgId, examCode, kind.name(), mimeType, sizeBytes, durationMs, filename, uploadedBy);
    }
}
