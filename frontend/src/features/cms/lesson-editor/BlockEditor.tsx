import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Input, Select } from '../../../design';
import { MediaPreview, MediaUploader, type MediaKind } from '../media';
import { useQuestionList } from '../questions/queries';
import { TextArea } from './fields';
import type { Block, Callout, Provider } from './types';

export interface BlockEditorProps {
  block: Block;
  onChange: (block: Block) => void;
  examCode: string;
  locked: boolean;
  /** Messages of the server for fields of THIS block, by field name (`url`, `imageMediaId`, ...). */
  errors: Record<string, string>;
}

/** The fields of one block, by its type. Nothing is validated here: the server holds every rule and answers with a JSON path per field. */
export function BlockEditor({ block, onChange, examCode, locked, errors }: BlockEditorProps) {
  const { t } = useTranslation('lessons');
  const set = (patch: Partial<Block>) => onChange({ ...block, ...patch } as Block);
  const caption = (b: { caption?: string }) => (
    <Input label={t('field.caption')} value={b.caption ?? ''} disabled={locked} onChange={(e) => set({ caption: e.target.value || undefined } as Partial<Block>)} error={errors.caption} />
  );
  switch (block.type) {
    case 'heading':
      return (
        <div className="flex flex-wrap gap-3">
          <Select
            label={t('field.level')}
            value={String(block.level)}
            disabled={locked}
            onValueChange={(v) => set({ level: Number(v) })}
            options={[2, 3, 4].map((l) => ({ value: String(l), label: `H${l}` }))}
            className="w-28"
          />
          <Input label={t('field.text')} value={block.text} disabled={locked} onChange={(e) => set({ text: e.target.value })} error={errors.text} className="min-w-72 flex-1" />
        </div>
      );
    case 'paragraph':
      return <TextArea label={t('field.text')} value={block.text} disabled={locked} onValue={(text) => set({ text })} error={errors.text} />;
    case 'table':
      return <TableFields block={block} locked={locked} onChange={onChange} errors={errors} />;
    case 'image':
      return (
        <div className="flex flex-col gap-3">
          <MediaField kind="IMAGE" label={t('field.imageMediaId')} value={block.imageMediaId} examCode={examCode} locked={locked} error={errors.imageMediaId} onValue={(v) => set({ imageMediaId: v })} />
          <Input label={t('field.alt')} hint={t('field.altHint')} value={block.alt} disabled={locked} onChange={(e) => set({ alt: e.target.value })} error={errors.alt} />
          {caption(block)}
        </div>
      );
    case 'audio':
      return (
        <div className="flex flex-col gap-3">
          <MediaField kind="AUDIO" label={t('field.audioMediaId')} value={block.audioMediaId} examCode={examCode} locked={locked} error={errors.audioMediaId} onValue={(v) => set({ audioMediaId: v })} />
          {caption(block)}
        </div>
      );
    case 'video-upload':
      return (
        <div className="flex flex-col gap-3">
          <MediaField kind="VIDEO" label={t('field.videoMediaId')} value={block.videoMediaId} examCode={examCode} locked={locked} error={errors.videoMediaId} onValue={(v) => set({ videoMediaId: v })} />
          {caption(block)}
        </div>
      );
    case 'video-embed':
      return (
        <div className="flex flex-col gap-3">
          <Select
            label={t('field.provider')}
            value={block.provider}
            disabled={locked}
            onValueChange={(v) => set({ provider: v as Provider })}
            options={[
              { value: 'youtube', label: 'YouTube' },
              { value: 'vimeo', label: 'Vimeo' },
            ]}
            className="w-48"
          />
          <Input label={t('field.url')} hint={t('field.urlHint')} type="url" value={block.url} disabled={locked} onChange={(e) => set({ url: e.target.value })} error={errors.url} />
          {caption(block)}
        </div>
      );
    case 'callout':
      return (
        <div className="flex flex-col gap-3">
          <Select
            label={t('field.kind')}
            value={block.kind}
            disabled={locked}
            onValueChange={(v) => set({ kind: v as Callout })}
            options={[
              { value: 'tip', label: t('callout.tip') },
              { value: 'trap', label: t('callout.trap') },
            ]}
            className="w-48"
          />
          <TextArea label={t('field.text')} value={block.text} disabled={locked} onValue={(text) => set({ text })} error={errors.text} />
        </div>
      );
    case 'embedded-question':
      return <EmbeddedQuestionField block={block} examCode={examCode} locked={locked} onChange={onChange} error={errors.questionVersionId} />;
    case 'worked-example':
      return (
        <div className="flex flex-col gap-3">
          <Input label={t('field.title')} value={block.title} disabled={locked} onChange={(e) => set({ title: e.target.value })} error={errors.title} />
          <TextArea label={t('field.problem')} value={block.problem} disabled={locked} onValue={(problem) => set({ problem })} error={errors.problem} />
          <TextArea
            label={t('field.steps')}
            hint={t('field.stepsHint')}
            value={block.steps.join('\n')}
            disabled={locked}
            onValue={(v) => set({ steps: v.split('\n') })}
            error={errors.steps}
          />
          <Input label={t('field.answer')} value={block.answer ?? ''} disabled={locked} onChange={(e) => set({ answer: e.target.value || undefined })} error={errors.answer} />
        </div>
      );
  }
}

function MediaField({ kind, label, value, examCode, locked, error, onValue }: { kind: MediaKind; label: string; value: string; examCode: string; locked: boolean; error?: string; onValue: (v: string) => void }) {
  const { t } = useTranslation('lessons');
  const [preview, setPreview] = useState(false);
  return (
    <div className="flex flex-col gap-2">
      <Input label={label} hint={t(`media.help.${kind}`)} value={value} disabled={locked} autoComplete="off" spellCheck={false} className="font-mono" onChange={(e) => onValue(e.target.value.trim())} error={error} />
      {value && (
        <>
          <Button variant="secondary" size="sm" className="self-start" aria-expanded={preview} onClick={() => setPreview(!preview)}>
            {preview ? t('media.hidePreview') : t('media.showPreview')}
          </Button>
          {preview && <MediaPreview mediaId={value} kind={kind} name={value} />}
        </>
      )}
      {!locked && (
        <details className="rounded-md border border-line bg-surface p-2">
          <summary className="cursor-pointer text-sm font-semibold text-ink">{t('media.uploadNew')}</summary>
          <div className="mt-2">
            <MediaUploader examCode={examCode} onUploaded={(m) => m.id && onValue(m.id)} />
          </div>
        </details>
      )}
    </div>
  );
}

function TableFields({ block, locked, onChange, errors }: { block: Extract<Block, { type: 'table' }>; locked: boolean; onChange: (b: Block) => void; errors: Record<string, string> }) {
  const { t } = useTranslation('lessons');
  // the author types text with bars between cells; the block holds the cells
  const [header, setHeader] = useState(block.header.join(' | '));
  const [rows, setRows] = useState(block.rows.map((r) => r.join(' | ')).join('\n'));
  const cells = (line: string) => line.split('|').map((c) => c.trim());
  return (
    <div className="flex flex-col gap-3">
      <Input
        label={t('field.tableHeader')}
        hint={t('field.tableHint')}
        value={header}
        disabled={locked}
        onChange={(e) => {
          setHeader(e.target.value);
          onChange({ ...block, header: cells(e.target.value) });
        }}
        error={errors.header}
      />
      <TextArea
        label={t('field.tableRows')}
        value={rows}
        disabled={locked}
        onValue={(v) => {
          setRows(v);
          onChange({ ...block, rows: v.split('\n').map(cells) });
        }}
        error={errors.rows}
      />
      <Input label={t('field.caption')} value={block.caption ?? ''} disabled={locked} onChange={(e) => onChange({ ...block, caption: e.target.value || undefined })} error={errors.caption} />
    </div>
  );
}

function EmbeddedQuestionField({ block, examCode, locked, onChange, error }: { block: Extract<Block, { type: 'embedded-question' }>; examCode: string; locked: boolean; onChange: (b: Block) => void; error?: string }) {
  const { t } = useTranslation('lessons');
  const list = useQuestionList({ exam: examCode || undefined });
  const options = (list.data?.items ?? []).map((q) => ({ value: q.versionId, label: `${q.title || t('embedded.untitled')} · ${q.typeCode} · ${t(`status.${q.status}`)}` }));
  return (
    <div className="flex flex-col gap-3">
      {options.length > 0 && (
        <Select
          label={t('embedded.pick')}
          placeholder={t('embedded.pickPlaceholder')}
          value={options.some((o) => o.value === block.questionVersionId) ? block.questionVersionId : undefined}
          disabled={locked}
          onValueChange={(v) => onChange({ ...block, questionVersionId: v })}
          options={options}
          className="max-w-xl"
        />
      )}
      <Input
        label={t('field.questionVersionId')}
        hint={t('embedded.hint')}
        value={block.questionVersionId}
        disabled={locked}
        autoComplete="off"
        spellCheck={false}
        className="font-mono"
        onChange={(e) => onChange({ ...block, questionVersionId: e.target.value.trim() })}
        error={error}
      />
    </div>
  );
}
