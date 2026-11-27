import pg from 'pg';
import { config } from './config.js';
import { DatabaseQueryResult } from './types.js';

const { Pool } = pg;

let pool: pg.Pool | null = null;

export function getDbPool(): pg.Pool {
  if (!pool) {
    pool = new Pool({
      connectionString: config.postgresUrl,
      connectionTimeoutMillis: 4000,
      idleTimeoutMillis: 10000,
      max: 5,
    });

    pool.on('error', (err) => {
      console.error('[English9x MCP DB Pool Error]:', err.message);
    });
  }
  return pool;
}

/**
 * Validates that an SQL query is strictly read-only.
 * Prevents any mutating or DDL SQL execution.
 */
export function isReadOnlySql(sql: string): { valid: boolean; reason?: string } {
  const cleaned = sql.trim().replace(/^--.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '').trim();
  if (!cleaned) {
    return { valid: false, reason: 'Empty SQL query' };
  }

  // Must start with SELECT, EXPLAIN, or WITH (Common Table Expressions)
  const startsWithRead = /^(SELECT|EXPLAIN|WITH)\b/i.test(cleaned);
  if (!startsWithRead) {
    return { valid: false, reason: 'Query must begin with SELECT, EXPLAIN, or WITH' };
  }

  // Prohibited mutating keywords anywhere as independent tokens
  const dangerousPattern = /\b(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|CREATE|GRANT|REVOKE|EXEC|EXECUTE|VACUUM|REINDEX|LOCK|CALL|COPY|DO)\b/i;
  if (dangerousPattern.test(cleaned)) {
    return { valid: false, reason: 'Query contains prohibited mutating statements (INSERT, UPDATE, DELETE, DROP, etc.)' };
  }

  // Prevent multiple statements (semicolon followed by another query)
  const statements = cleaned.split(';').map((s) => s.trim()).filter(Boolean);
  if (statements.length > 1) {
    return { valid: false, reason: 'Multiple SQL statements in a single query are not allowed' };
  }

  return { valid: true };
}

/**
 * Execute a safe, read-only SQL query against English9x PostgreSQL database.
 */
export async function executeReadOnlyQuery(
  sql: string,
  params: any[] = [],
  maxRows = 100
): Promise<DatabaseQueryResult> {
  const check = isReadOnlySql(sql);
  if (!check.valid) {
    throw new Error(`Forbidden SQL: ${check.reason}`);
  }

  const p = getDbPool();
  const client = await p.connect();
  try {
    // Enforce read-only transaction and strict query timeout
    await client.query('SET TRANSACTION READ ONLY');
    await client.query('SET statement_timeout = 5000');

    const res = await client.query(sql, params);
    const rows = res.rows.slice(0, maxRows);

    return {
      command: res.command,
      rowCount: Math.min(res.rowCount || 0, maxRows),
      fields: res.fields.map((f) => ({ name: f.name, dataTypeId: f.dataTypeID })),
      rows,
    };
  } finally {
    client.release();
  }
}

/**
 * Fetch overview statistics of database tables in English9x.
 */
export async function getDatabaseStats(): Promise<Record<string, any>> {
  const sql = `
    SELECT
      relname AS table_name,
      n_live_tup AS estimated_row_count
    FROM pg_stat_user_tables
    ORDER BY n_live_tup DESC;
  `;
  const result = await executeReadOnlyQuery(sql);
  return {
    tables: result.rows,
    queriedAt: new Date().toISOString(),
  };
}
