# English9x Backend MCP Server

A comprehensive, production-grade [Model Context Protocol (MCP)](https://modelcontextprotocol.io) server providing AI agents (Antigravity, Claude Desktop, Cursor, VS Code) with full visibility, orchestration, and querying capabilities for the English9x Backend platform.

---

## 🌟 Key Capabilities

The MCP server connects directly to the English9x backend (Spring Boot) and PostgreSQL database, exposing **18 specialized tools**, **3 real-time resources**, and **3 AI pedagogical prompts**:

### 🛠️ Tool Catalog (18 Tools)

1. **System & Database Diagnostics**:
   - `e9x_health_check`: Dual liveness probe for Backend API (`/api/platform/ping`) and PostgreSQL database.
   - `e9x_database_query`: Safe, strictly read-only SQL query execution with AST validation (SELECT, EXPLAIN, WITH only).
   - `e9x_database_stats`: Table catalog with live row counts and storage estimates.

2. **Identity & Authentication**:
   - `e9x_auth_login`: Authenticate as admin or learner to acquire session JWT tokens.
   - `e9x_get_context`: Query current authenticated user profile, roles, and organization scope.
   - `e9x_list_users`: Query registered platform users and assigned roles.

3. **Courses & Public Catalog**:
   - `e9x_list_courses`: List all published courses with chapter and lesson counts.
   - `e9x_get_course_outline`: Full curriculum syllabus and unlock rules by 6-char course code.
   - `e9x_search_catalog`: Search courses by keyword across titles and descriptions.
   - `e9x_get_score_table`: Query score conversion tables (raw correct to scaled score).

4. **Tests & Question Authoring**:
   - `e9x_list_tests`: List test versions and drafts.
   - `e9x_get_test_details`: Inspect sections, parts, durations, and question groups of an exam.
   - `e9x_inspect_question`: Inspect question prompts, answer keys, explanations, and distractors.

5. **Exam Room & Taking**:
   - `e9x_create_attempt`: Start a new exam attempt on a test version.
   - `e9x_get_attempt_state`: Real-time attempt status, time remaining, sections, and answered questions.
   - `e9x_save_answers`: Autosave learner answers to question parts.
   - `e9x_submit_attempt`: Finalize and submit exam attempt for immediate scoring.

6. **Results & AI Radar**:
   - `e9x_get_exam_result`: Full attempt scorecard, scaled scores per skill, explanations, and transcripts.
   - `e9x_get_weakness_radar`: Weak question types ranking and targeted remedial lessons.
   - `e9x_get_ai_feedback`: Detailed rubrics and scoring feedback for Writing/Speaking submissions.

7. **Classroom & B2B Cohorts**:
   - `e9x_list_classes`: List classes and cohorts in an organization.
   - `e9x_get_class_details`: Class metadata and teacher assignment.
   - `e9x_get_class_members`: Student roster enrolled in a class.

8. **Billing & Subscriptions**:
   - `e9x_list_plans`: Active subscription plans on sale.
   - `e9x_list_orders`: Learner orders and payment states.
   - `e9x_get_licenses`: Enterprise licenses and seat allocation statuses.

---

### 📦 Resources

- `e9x://system/status`: Real-time backend and database operational status.
- `e9x://catalog/courses`: Current published courses JSON list.
- `e9x://schema/database`: Database table catalog and live row statistics.

---

### 💡 Prompts

- `diagnose_learner_weakness`: Generates a personalized 4-week remedial roadmap based on weak question types.
- `generate_toeic_question`: Synthesizes authentic TOEIC Part 5 questions with comprehensive Vietnamese explanations.
- `grade_writing_submission`: Evaluates IELTS/TOEIC Writing essays against CEFR rubric criteria.

---

## 🚀 Quick Start

### 1. Build and Test

```bash
cd mcp
pnpm install
pnpm run build
pnpm test
```

### 2. Configure for Antigravity

Add the server to `~/.gemini/config/mcp_config.json` or `.agents/mcp_config.json`:

```json
{
  "mcpServers": {
    "english9x-backend": {
      "command": "node",
      "args": ["/workspace/english9x/mcp/dist/index.js"],
      "env": {
        "E9X_API_URL": "http://localhost:8080",
        "E9X_POSTGRES_URL": "postgresql://e9x:e9x_dev_password@localhost:5432/e9x"
      }
    }
  }
}
```

### 3. Configure for Claude Desktop / Cursor

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "english9x-backend": {
      "command": "node",
      "args": ["/absolute/path/to/english9x/mcp/dist/index.js"],
      "env": {
        "E9X_API_URL": "http://localhost:8080"
      }
    }
  }
}
```

---

## 🛡️ Security

- **Strict Read-Only Database Queries**: `e9x_database_query` uses an AST tokenizer that validates queries start strictly with `SELECT`, `EXPLAIN`, or `WITH`. Any mutating statement (`INSERT`, `UPDATE`, `DELETE`, `DROP`, `ALTER`, `TRUNCATE`, `GRANT`, `DO`) or multi-statement injection is rejected.
- **Connection Isolation**: DB queries execute within an explicit `SET TRANSACTION READ ONLY` session with a 5000ms query timeout.
