#!/usr/bin/env node
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import http from 'node:http';
import { config } from './config.js';
import { registerAllTools } from './tools/index.js';
import { registerResources } from './resources.js';
import { registerPrompts } from './prompts.js';

export function createServer(): McpServer {
  const server = new McpServer({
    name: 'english9x-backend',
    version: '1.0.0',
  });

  registerAllTools(server);
  registerResources(server);
  registerPrompts(server);

  return server;
}

async function main() {
  const args = process.argv.slice(2);
  const portArgIndex = args.indexOf('--port');
  const port =
    portArgIndex !== -1 && args[portArgIndex + 1]
      ? parseInt(args[portArgIndex + 1], 10)
      : config.port;

  const server = createServer();

  if (port) {
    // Simple HTTP / Health check server with diagnostics
    const httpServer = http.createServer(async (req, res) => {
      if (req.url === '/health' || req.url === '/ping') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ status: 'ok', server: 'english9x-backend-mcp', version: '1.0.0' }));
        return;
      }
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'Endpoint not found. Use stdio transport for MCP communication.' }));
    });

    httpServer.listen(port, () => {
      console.error(`[English9x MCP] HTTP health check running on port ${port}`);
    });
  }

  // Connect stdio transport
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error('[English9x MCP] Server connected via Stdio transport. Ready for tool calls.');

  // Handle process shutdown
  const shutdown = async () => {
    console.error('[English9x MCP] Shutting down server...');
    await server.close();
    process.exit(0);
  };

  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

// Auto-run when executed directly
if (import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith('dist/index.js') || process.argv[1]?.endsWith('src/index.ts')) {
  main().catch((err) => {
    console.error('[English9x MCP] Fatal error starting server:', err);
    process.exit(1);
  });
}
