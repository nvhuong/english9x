// Local ESLint plugin "e9x". Rule: every query key must be built by ctxKey / useCtxKey / publicKey, so cached data is
// always scoped to the organisation context (or explicitly public). See src/app/stores/context.ts.
//
// Where a key is checked:
//   1. any `queryKey:` property (useQuery, useQueries' list, queryOptions, the filters of the query client);
//   2. the key given straight to a query-client method: `qc.setQueryData(['x'], d)`, `qc.invalidateQueries(['x'])`, ...
//      (reading and writing the cache with a literal key is the way a stale or foreign entry gets hit);
//   3. the FIRST argument of ctxKey: it is what scopes the key, so it must be the context (an identifier, a member, a call or null),
//      never a string or a template: `ctxKey('cms', 'x')` would put the section where the organisation goes.
const KEY_HELPERS = new Set(['ctxKey', 'useCtxKey', 'publicKey']);

// Methods whose first argument is a key (array) or a filter object (`{ queryKey }`, `{ predicate }`).
const CLIENT_METHODS = new Set([
  'invalidateQueries', 'removeQueries', 'cancelQueries', 'resetQueries', 'refetchQueries',
  'getQueryData', 'setQueryData', 'getQueryState', 'ensureQueryData', 'prefetchQuery', 'fetchQuery', 'getQueriesData', 'setQueriesData',
]);

const isHelperCall = (n) => n.type === 'CallExpression' && n.callee.type === 'Identifier' && KEY_HELPERS.has(n.callee.name);

const queryKeyHelper = {
  meta: {
    type: 'problem',
    schema: [],
    messages: {
      bad: 'queryKey must be built with ctxKey(...)/useCtxKey(...) (context-scoped) or publicKey(...) (context-free).',
      noContext: 'The first argument of ctxKey is the organisation context (ctxKey(orgId, ...parts)), not a part of the key.',
    },
  },
  create(context) {
    return {
      Property(node) {
        const name = node.key.type === 'Identifier' ? node.key.name : node.key.type === 'Literal' ? node.key.value : null;
        if (node.computed || name !== 'queryKey') return;
        if (isHelperCall(node.value)) return;
        context.report({ node: node.value, messageId: 'bad' });
      },
      CallExpression(node) {
        const callee = node.callee;
        if (isHelperCall(node) && node.callee.name === 'ctxKey') {
          const first = node.arguments[0];
          if (first && (first.type === 'Literal' ? first.value !== null : first.type === 'TemplateLiteral')) {
            context.report({ node: first, messageId: 'noContext' });
          }
          return;
        }
        if (callee.type !== 'MemberExpression' || callee.computed || callee.property.type !== 'Identifier') return;
        if (!CLIENT_METHODS.has(callee.property.name)) return;
        const first = node.arguments[0];
        if (!first) return;
        // an object is a filter: its `queryKey` property is checked by the Property visitor; a bare key must be a helper call
        if (first.type === 'ObjectExpression' || first.type === 'Identifier' || first.type === 'SpreadElement') return;
        if (isHelperCall(first)) return;
        context.report({ node: first, messageId: 'bad' });
      },
    };
  },
};

export default { rules: { 'query-key-helper': queryKeyHelper } };
