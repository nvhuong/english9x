import { describe, expect, it } from 'vitest';
import i18n from '../../../i18n';
import { errorSchemaOf, draftOf, formSchema, formUiSchema, mediaKindOf, modelOf, pathOf } from './schema';
import { QUESTION, TOEIC_P3 } from './testUtils';

const t = i18n.getFixedT('vi', 'questions');
const exists = (key: string) => i18n.exists(`questions:${key}`);

describe('question form schema', () => {
  it('puts the content, the answer key, the explanation and the points in one schema, without the dialect marker the validator does not know', () => {
    const s = formSchema(TOEIC_P3, t);
    expect(Object.keys(s.properties ?? {})).toEqual(['content', 'answerKey', 'explanation', 'points']);
    expect(JSON.stringify(s)).not.toContain('$schema');
    expect((s.properties!.content as { properties: object }).properties).toHaveProperty('options');
  });

  it('shows the media id fields with the media widget and gives long texts a text area', () => {
    const ui = formUiSchema(TOEIC_P3, t, exists);
    expect((ui.content as Record<string, unknown>).audioMediaId).toMatchObject({ 'ui:widget': 'mediaId', 'ui:options': { kind: 'AUDIO' } });
    expect((ui.answerKey as { transcript: unknown }).transcript).toMatchObject({ 'ui:widget': 'textarea' });
    expect((ui.content as { stem: unknown }).stem).toMatchObject({ 'ui:title': 'Câu hỏi' });
    expect(mediaKindOf('imageMediaId')).toBe('IMAGE');
    expect(mediaKindOf('videoMediaId')).toBe('VIDEO');
    expect(mediaKindOf('stem')).toBeUndefined();
  });

  it('turns the JSON paths of the server\'s errors into the form\'s error tree', () => {
    expect(pathOf('content.options[2].key')).toEqual(['content', 'options', 2, 'key']);
    expect(pathOf('answerKey.slots[0][1]')).toEqual(['answerKey', 'slots', 0, 1]);
    const tree = errorSchemaOf([
      { field: 'content.options[2].key', message: 'does not match' },
      { field: 'content.stem', message: 'is required' },
      { field: 'content.stem', message: 'is too short' },
      { field: 'points', message: 'must be above 0' },
      { field: null, message: 'ignored: no path' },
    ]);
    expect(tree).toEqual({
      content: { options: { 2: { key: { __errors: ['does not match'] } } }, stem: { __errors: ['is required', 'is too short'] } },
      points: { __errors: ['must be above 0'] },
    });
  });

  it('sends the explanation only when there is one, and the type with every save', () => {
    const m = modelOf(QUESTION);
    expect(draftOf('TOEIC_P3_CONVERSATION', m)).toMatchObject({ typeCode: 'TOEIC_P3_CONVERSATION', explanation: 'Because.', points: 1 });
    expect(draftOf('TOEIC_P3_CONVERSATION', { ...m, explanation: '  ' })).not.toHaveProperty('explanation');
  });
});
