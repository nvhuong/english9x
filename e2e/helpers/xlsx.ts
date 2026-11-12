// A tiny .xlsx reader for the specs: an .xlsx is a zip of XML files. Enough for what the server writes (Apache POI: shared strings + numeric cells, one sheet).
import { inflateRawSync } from 'node:zlib';

function unzip(buf: Buffer): Map<string, Buffer> {
  const files = new Map<string, Buffer>();
  // end of central directory
  let eocd = buf.length - 22;
  while (eocd >= 0 && buf.readUInt32LE(eocd) !== 0x06054b50) eocd--;
  if (eocd < 0) throw new Error('not a zip');
  const entries = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  for (let i = 0; i < entries; i++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('bad central directory');
    const method = buf.readUInt16LE(p + 10);
    const size = buf.readUInt32LE(p + 20);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const local = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
    const dataStart = local + 30 + buf.readUInt16LE(local + 26) + buf.readUInt16LE(local + 28);
    const raw = buf.subarray(dataStart, dataStart + size);
    files.set(name, method === 0 ? raw : inflateRawSync(raw));
    p += 46 + nameLen + extraLen + commentLen;
  }
  return files;
}

const unescapeXml = (s: string) => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');

export interface Sheet {
  name: string;
  /** rows[r][c]: a string, a number, or null for an empty cell; row 0 is the header. */
  rows: Array<Array<string | number | null>>;
}

export function readXlsx(buf: Buffer): Sheet[] {
  const files = unzip(buf);
  const text = (n: string) => (files.get(n) ?? Buffer.alloc(0)).toString('utf8');
  const shared = [...text('xl/sharedStrings.xml').matchAll(/<si>([\s\S]*?)<\/si>/g)].map((m) =>
    unescapeXml([...m[1]!.matchAll(/<t[^>]*>([\s\S]*?)<\/t>/g)].map((t) => t[1]!).join('')),
  );
  const names = [...text('xl/workbook.xml').matchAll(/<sheet [^>]*name="([^"]*)"/g)].map((m) => unescapeXml(m[1]!));
  return names.map((name, i) => {
    const rows: Sheet['rows'] = [];
    for (const row of text(`xl/worksheets/sheet${i + 1}.xml`).matchAll(/<row [^>]*r="(\d+)"[^>]*>([\s\S]*?)<\/row>/g)) {
      const r = Number(row[1]) - 1;
      rows[r] = rows[r] ?? [];
      for (const c of row[2]!.matchAll(/<c r="([A-Z]+)\d+"([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/g)) {
        const col = [...c[1]!].reduce((n, ch) => n * 26 + ch.charCodeAt(0) - 64, 0) - 1;
        const v = /<v>([\s\S]*?)<\/v>/.exec(c[3] ?? '')?.[1];
        if (v === undefined) continue;
        rows[r]![col] = /t="s"/.test(c[2]!) ? shared[Number(v)]! : Number(v);
      }
    }
    return { name, rows: rows.map((r) => Array.from(r ?? [], (x) => x ?? null)) };
  });
}
