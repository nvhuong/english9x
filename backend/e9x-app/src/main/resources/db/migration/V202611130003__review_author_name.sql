-- P2.9 review screens: the author's display name for a reviewer.
--
-- Since P1.5 the users policy shows a person only their own row (plus the members an ORG_ADMIN manages), so a REVIEWER of English9x cannot read
-- the row of the colleague whose version they are reviewing. This function is the ONE narrow way: it returns the display name (a bare text,
-- no email, no id, no other column) of the author of an OPEN-or-decided review that the caller may act on, i.e. one that
--   * is in the context of the session (the organisation; or English9x with the staff flag), and
--   * whose exam the caller holds the REVIEWER role for in that context.
-- Given a bundle id instead of a review id it answers for the oldest member the caller may act on. Anything else (no such review, another
-- context, another exam, no REVIEWER grant, no signed-in person) is NULL, indistinguishable from "no name".
-- The caller's own grants are read through the same invoker helpers the review_requests policy uses; the function itself runs as the owner
-- only to read users.display_name.
CREATE FUNCTION e9x_review_author_name(p_review_or_bundle uuid) RETURNS text
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT u.display_name
    FROM review_requests r
    JOIN users u ON u.id = r.author_id
   WHERE (r.id = p_review_or_bundle OR r.bundle_id = p_review_or_bundle)
     AND e9x_current_user() IS NOT NULL
     AND r.owner_org_id IS NOT DISTINCT FROM e9x_current_org()
     AND (r.owner_org_id IS NOT NULL OR e9x_is_staff())
     AND EXISTS (SELECT 1 FROM role_grants g
                  WHERE g.user_id = e9x_current_user()
                    AND g.org_id IS NOT DISTINCT FROM r.owner_org_id
                    AND g.role = 'REVIEWER'
                    AND g.exam_code = r.exam_code)
   ORDER BY r.submitted_at, r.id
   LIMIT 1 $$;
REVOKE ALL ON FUNCTION e9x_review_author_name(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION e9x_review_author_name(uuid) TO e9x_app;
