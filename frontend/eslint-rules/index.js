// Local ESLint plugin "e9x". Rule: every `queryKey:` must be built by ctxKey / useCtxKey / publicKey, so cached data is
// always scoped to the organisation context (or explicitly public). See src/app/stores/context.ts.
const ALLOWED = new Set(['ctxKey', 'useCtxKey', 'publicKey']);

const queryKeyHelper = {
  meta: {
    type: 'problem',
    schema: [],
    messages: { bad: 'queryKey must be built with ctxKey(...)/useCtxKey(...) (context-scoped) or publicKey(...) (context-free).' },
  },
  create(context) {
    return {
      Property(node) {
        const name = node.key.type === 'Identifier' ? node.key.name : node.key.type === 'Literal' ? node.key.value : null;
        if (node.computed || name !== 'queryKey') return;
        const v = node.value;
        if (v.type === 'CallExpression' && v.callee.type === 'Identifier' && ALLOWED.has(v.callee.name)) return;
        context.report({ node: v, messageId: 'bad' });
      },
    };
  },
};

export default { rules: { 'query-key-helper': queryKeyHelper } };
