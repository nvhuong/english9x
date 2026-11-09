-- P2.1: role_grants.exam_code names an exam of the catalog. Until now only RoleRules.KNOWN_EXAMS (a constant) said which codes were
-- valid; the catalog (V202611100002 seeds IELTS and TOEIC) is the one list, in the database as well as in the service.
-- Every grant that can exist today passed RoleRules, so only IELTS and TOEIC occur and the constraint validates against the seeded exams.
ALTER TABLE role_grants ADD CONSTRAINT role_grants_exam_code_fk FOREIGN KEY (exam_code) REFERENCES exams(code);
