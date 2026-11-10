import { describe, expect, it } from 'vitest';
import { DEFAULT_TEMPLATE, draftOf, emptyModel, modelOf, schemaOf } from './model';
import type { SkillVersion } from './types';

describe('the skill form model', () => {
  it('starts with the four prompt variables and one criterion, and sends numbers as numbers and an empty number as nothing', () => {
    const m = emptyModel();
    for (const v of ['{{rubric}}', '{{samples}}', '{{task}}', '{{response}}']) expect(DEFAULT_TEMPLATE).toContain(v);
    m.criteria[0] = { ...m.criteria[0]!, code: ' task_response ', name: 'Task response', min: '0', max: '9', step: '' };
    m.resultSchema = '{"type":"object"}';
    const d = draftOf('IELTS_W_TASK2', m);
    expect(d.criteria).toEqual([{ code: 'task_response', name: 'Task response', min: 0, max: 9, step: undefined }]);
    expect(d.resultSchema).toEqual({ type: 'object' });
  });

  it('sends a sample with its text or its file, and leaves out what is empty', () => {
    const m = emptyModel();
    m.samples = [
      { key: 'a', band: '6.5', text: 'An essay', mediaId: '', commentary: '' },
      { key: 'b', band: '8', text: '', mediaId: ' 7d6e5f40-1b2c-4d3e-8f90-a1b2c3d4e5f6 ', commentary: 'Fluent' },
    ];
    expect(draftOf('IELTS_S_PART2', m).samples).toEqual([
      { band: 6.5, text: 'An essay' },
      { band: 8, mediaId: '7d6e5f40-1b2c-4d3e-8f90-a1b2c3d4e5f6', commentary: 'Fluent' },
    ]);
  });

  it('cannot send a result schema it cannot read', () => {
    const m = emptyModel();
    m.resultSchema = '{"type": ';
    expect(() => draftOf('IELTS_W_TASK2', m)).toThrow(SyntaxError);
  });

  it('generates a result schema that declares a property for every criterion code', () => {
    const m = emptyModel();
    m.criteria = [
      { key: '1', code: 'task_response', name: 'TR', min: '0', max: '9', step: '0.5' },
      { key: '2', code: 'grammar', name: 'G', min: '0', max: '9', step: '0.5' },
    ];
    const schema = JSON.parse(schemaOf(m.criteria)) as { properties: Record<string, unknown>; required: string[] };
    expect(Object.keys(schema.properties)).toEqual(['task_response', 'grammar', 'feedback']);
    expect(schema.required).toEqual(['task_response', 'grammar', 'feedback']);
  });

  it('shows a saved skill as the text the form edits, and sends it back unchanged', () => {
    const v = {
      rubric: 'r',
      criteria: [{ code: 'a', name: 'A', min: 0, max: 9, step: 0.5 }],
      promptTemplate: DEFAULT_TEMPLATE,
      samples: [{ band: 7, text: 'x', commentary: 'y' }],
      resultSchema: { type: 'object', properties: { a: { type: 'number' } } },
    } as unknown as SkillVersion;
    expect(draftOf('IELTS_W_TASK2', modelOf(v))).toEqual({
      questionTypeCode: 'IELTS_W_TASK2',
      rubric: 'r',
      criteria: v.criteria,
      promptTemplate: DEFAULT_TEMPLATE,
      samples: v.samples,
      resultSchema: v.resultSchema,
    });
  });
});
