import sax from 'sax';
import { Readable } from 'node:stream';

/**
 * XMLTV "20261102183000 +1300" → epoch ms. A time without an offset is read as UTC, as DPTV-Server writes
 * every time with one.
 */
export function parseXmltvTime(raw) {
  const m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-]\d{4})?/.exec(String(raw || '').trim());
  if (!m) return null;
  const [, y, mo, d, h, mi, s = '00', off] = m;
  let ms = Date.UTC(+y, +mo - 1, +d, +h, +mi, +s);
  if (off) {
    const sign = off[0] === '-' ? -1 : 1;
    ms -= sign * (Number(off.slice(1, 3)) * 60 + Number(off.slice(3, 5))) * 60_000;
  }
  return ms;
}

/**
 * Reads an XMLTV document as it streams in (it can be tens of MB): channel id → its programmes sorted by
 * start, each { start, stop, title, desc }. Only programmes ending after [fromMs] are kept.
 */
export async function parseXmltv(source, { fromMs = 0 } = {}) {
  const parser = sax.createStream(false, { lowercase: true, trim: false });
  const byChannel = new Map();
  let prog = null;
  let field = null;

  parser.on('opentag', (node) => {
    if (node.name === 'programme') {
      prog = {
        channel: node.attributes.channel,
        start: parseXmltvTime(node.attributes.start),
        stop: parseXmltvTime(node.attributes.stop),
        title: '',
        desc: '',
      };
    } else if (prog && (node.name === 'title' || node.name === 'desc')) {
      field = node.name;
    }
  });
  parser.on('text', (t) => { if (prog && field) prog[field] += t; });
  parser.on('cdata', (t) => { if (prog && field) prog[field] += t; });
  parser.on('closetag', (name) => {
    if (name === 'title' || name === 'desc') field = null;
    else if (name === 'programme' && prog) {
      if (prog.channel && prog.start != null && prog.stop != null && prog.stop > fromMs) {
        let list = byChannel.get(prog.channel);
        if (!list) byChannel.set(prog.channel, (list = []));
        list.push({ start: prog.start, stop: prog.stop, title: prog.title.trim(), desc: prog.desc.trim() });
      }
      prog = null;
    }
  });

  await new Promise((resolve, reject) => {
    parser.on('error', function onError(err) {
      // A stray character should not lose the whole guide: note it and carry on.
      console.warn(`[xmltv] parse warning: ${err.message.split('\n')[0]}`);
      this._parser.error = null;
      this._parser.resume();
    });
    parser.on('end', resolve);
    const stream = source instanceof Readable ? source : Readable.from(source);
    stream.on('error', reject);
    stream.pipe(parser);
  });
  for (const list of byChannel.values()) list.sort((a, b) => a.start - b.start);
  return byChannel;
}

/** The programmes of [list] that overlap [fromMs, toMs). */
export function inWindow(list, fromMs, toMs) {
  if (!list) return [];
  return list.filter((p) => p.stop > fromMs && p.start < toMs);
}
