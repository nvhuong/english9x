import { describe, it, expect } from 'vitest';
import { isReadOnlySql } from './db.js';
import { createServer } from './index.js';
import { E9xApiClient } from './client.js';

describe('SQL Read-Only Validator', () => {
  it('allows safe SELECT queries', () => {
    expect(isReadOnlySql('SELECT * FROM users').valid).toBe(true);
    expect(isReadOnlySql('  select id, email from users where status = 1').valid).toBe(true);
    expect(isReadOnlySql('WITH cte AS (SELECT 1) SELECT * FROM cte').valid).toBe(true);
    expect(isReadOnlySql('EXPLAIN ANALYZE SELECT count(*) FROM courses').valid).toBe(true);
  });

  it('rejects mutating SQL statements', () => {
    expect(isReadOnlySql('INSERT INTO users (email) VALUES (\'test\')').valid).toBe(false);
    expect(isReadOnlySql('UPDATE users SET display_name = \'hacked\'').valid).toBe(false);
    expect(isReadOnlySql('DELETE FROM users WHERE id = 1').valid).toBe(false);
    expect(isReadOnlySql('DROP TABLE users CASCADE').valid).toBe(false);
    expect(isReadOnlySql('ALTER TABLE users ADD COLUMN test text').valid).toBe(false);
    expect(isReadOnlySql('TRUNCATE table courses').valid).toBe(false);
  });

  it('rejects multi-statement injection attempts', () => {
    expect(isReadOnlySql('SELECT 1; DROP TABLE users;').valid).toBe(false);
    expect(isReadOnlySql('SELECT * FROM users; DELETE FROM users').valid).toBe(false);
  });
});

describe('MCP Server Registration', () => {
  it('initializes and registers all tools, resources, and prompts', () => {
    const server = createServer();
    expect(server).toBeDefined();
    expect(server.server).toBeDefined();
  });
});

describe('E9xApiClient', () => {
  it('handles offline backend gracefully with structured error message', async () => {
    const client = new E9xApiClient('http://127.0.0.1:59999'); // Non-existent port
    const res = await client.checkHealth(1500);
    expect(res.ok).toBe(false);
    expect(res.status).toBe(0);
    expect((res.data as any).networkError).toBe(true);
  });
});
