import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { registerSystemTools } from './system.js';
import { registerAuthTools } from './auth.js';
import { registerCatalogTools } from './catalog.js';
import { registerTestsTools } from './tests.js';
import { registerExamTools } from './exam.js';
import { registerResultsTools } from './results.js';
import { registerClassroomTools } from './classroom.js';
import { registerBillingTools } from './billing.js';

export function registerAllTools(server: McpServer) {
  registerSystemTools(server);
  registerAuthTools(server);
  registerCatalogTools(server);
  registerTestsTools(server);
  registerExamTools(server);
  registerResultsTools(server);
  registerClassroomTools(server);
  registerBillingTools(server);
}
