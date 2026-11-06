package com.english9x.org.sweep;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Two tenants with data in several RLS tables, seeded as superuser. A is the caller; B owns the foreign ids. */
public final class SweepFixtures {

    public final UUID orgA = UUID.randomUUID();
    public final UUID orgB = UUID.randomUUID();
    public final UUID userA = UUID.randomUUID();
    public final UUID userB = UUID.randomUUID();
    public final UUID classA = UUID.randomUUID();
    public final UUID classB = UUID.randomUUID();

    private final JdbcTemplate su;

    public SweepFixtures(JdbcTemplate superuser) {
        this.su = superuser;
    }

    public void seed() {
        su.update("insert into organizations(id,name,type) values (?,'A','CENTER'),(?,'B','CENTER')", orgA, orgB);
        su.update("insert into users(id,email) values (?,'a@x.test'),(?,'b@x.test')", userA, userB);
        su.update("insert into org_memberships(org_id,user_id) values (?,?),(?,?)", orgA, userA, orgB, userB);
        su.update("insert into classes(id,owner_org_id,name) values (?,?,'class A'),(?,?,'class B')", classA, orgA, classB, orgB);
        su.update("insert into exams(code,name) values ('IELTS','IELTS') on conflict do nothing");
        su.update("insert into courses(owner_org_id,exam_code,code,title,status) values (?,'IELTS','B00001','B draft','DRAFT')", orgB);
        su.update("insert into questions(owner_org_id,exam_code,content,answer_key,status) values (?,'IELTS','{}','{}','DRAFT')", orgB);
        su.update("insert into exam_attempts(user_id,owner_org_id,test_version_id,status) values (?,?,gen_random_uuid(),'IN_PROGRESS')", userB, orgB);
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,null,'ORG_ADMIN')", userB, orgB);
    }

    /**
     * Every id that belongs to org B and that org A may not see: all rows of any public table that carries an
     * {@code id uuid} and an {@code owner_org_id} / {@code org_id} equal to B, or a {@code user_id} of a user who
     * belongs only to B, plus B itself. Tables added by later tasks are found by this catalog query automatically.
     */
    public List<UUID> foreignIds() {
        List<UUID> ids = new ArrayList<>(List.of(orgB, userB));
        List<String> tables = su.queryForList("""
                select c.table_name from information_schema.columns c
                 where c.table_schema = 'public' and c.column_name = 'id' and c.data_type = 'uuid'
                   and c.table_name <> 'flyway_schema_history'
                """, String.class);
        for (String t : tables) {
            List<String> cols = su.queryForList(
                    "select column_name from information_schema.columns where table_schema='public' and table_name=?", String.class, t);
            List<String> conds = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            for (String col : List.of("owner_org_id", "org_id")) {
                if (cols.contains(col)) {
                    conds.add(col + " = ?");
                    args.add(orgB);
                }
            }
            if (cols.contains("user_id")) {
                conds.add("user_id in (select user_id from org_memberships where org_id = ?)");
                args.add(orgB);
            }
            if (conds.isEmpty()) {
                continue;
            }
            String sql = "select id from \"" + t + "\" where " + String.join(" or ", conds);
            ids.addAll(su.queryForList(sql, UUID.class, args.toArray()));
        }
        return ids.stream().distinct().toList();
    }
}
