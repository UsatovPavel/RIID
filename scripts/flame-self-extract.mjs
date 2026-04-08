/**
 * Parses async-profiler riid-cpu.html, builds frame tree from levels[],
 * computes self = inclusive - sum(children), lists frames with self/total > 0.1%.
 */
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const htmlPath = path.resolve(process.argv[2] || path.join('..', 'riid-cpu.html'));
const outPath = path.resolve(process.argv[3] || path.join('..', 'Шапки.md'));

const html = fs.readFileSync(htmlPath, 'utf8');
const m = html.match(/<script>\s*([\s\S]*?)<\/script>/);
if (!m) throw new Error('No <script> in HTML');

let code = m[1].replace(/\bsearch\(\)\s*;/, '');
// const/let из flame HTML не обязаны стать свойствами sandbox; в конце того же
// скрипта levels ещё в зоне видимости — кладём на globalThis контекста (это sandbox).
code += '\n;globalThis.__flameExtractLevels = levels;\n';

const sandbox = {
  console,
  document: {
    getElementById() {
      return {
        getContext() {
          return {
            scale() {},
            fillRect() {},
            fillText() {},
          };
        },
        style: {},
        offsetWidth: 800,
        offsetHeight: 1472,
        width: 800,
        height: 1472,
        title: '',
        cursor: '',
        onmousemove: null,
        onmouseout: null,
        ondblclick: null,
        onclick: null,
        offsetLeft: 0,
        offsetTop: 0,
      };
    },
    body: { style: { font: '12px Verdana' } },
  },
  devicePixelRatio: 1,
  window: {},
  getSelection() {
    return { removeAllRanges() {}, selectAllChildren() {} };
  },
  event: {},
  prompt: () => null,
};
sandbox.window = sandbox;

vm.createContext(sandbox);
vm.runInContext(code, sandbox);

const levels = sandbox.__flameExtractLevels;
if (!levels) {
      throw new Error(
            'No __flameExtractLevels after eval — broken flame HTML or vm context (async-profiler viewer script expected)'
      );
}
const allFrames = [];
for (let h = 0; h < levels.length; h++) {
      for (const f of levels[h]) {
            allFrames.push(f);
      }
}

const root = levels[0][0];
const total = root.width;

function findParent(child) {
      if (child.level === 0) return null;
      const L = child.level - 1;
      const row = levels[L];
      let best = null;
      let bestW = Infinity;
      const c0 = child.left;
      const c1 = child.left + child.width;
      for (const p of row) {
            const p0 = p.left;
            const p1 = p.left + p.width;
            if (p0 <= c0 && c1 <= p1) {
                  const w = p.width;
                  if (w < bestW) {
                        bestW = w;
                        best = p;
                  }
            }
      }
      return best;
}

const children = new Map();
for (const f of allFrames) {
      children.set(f, []);
}
for (const f of allFrames) {
      const p = findParent(f);
      if (p) {
            if (!children.has(p)) children.set(p, []);
            children.get(p).push(f);
      }
}

const selfMap = new Map();
for (const f of allFrames) {
      const ch = children.get(f) || [];
      const sumCh = ch.reduce((s, c) => s + c.width, 0);
      const self = f.width - sumCh;
      selfMap.set(f, self);
}

const rows = [];
for (const f of allFrames) {
      const self = selfMap.get(f);
      const selfPct = (100 * self) / total;
      if (selfPct > 0.1) {
            rows.push({
                  title: f.title,
                  selfPct,
                  self,
                  inclusivePct: (100 * f.width) / total,
            });
      }
}

rows.sort((a, b) => b.selfPct - a.selfPct);

const lines = [
      '# Шапки колонок профиля (exclusive > 0.1% от всех сэмплов)',
      '',
      `Всего сэмплов (root): **${total}**. Критерий: «self = inclusive − Σ(дети)» в процентах от total.`,
      '',
      '| № | Функция | Self % | Self (сэмплы) | Inclusive % |',
      '|---|---------|--------|---------------|-------------|',
];

rows.forEach((r, i) => {
      const name = r.title.replace(/\|/g, '\\|');
      lines.push(
            `| ${i + 1} | ${name} | ${r.selfPct.toFixed(2)} | ${r.self} | ${r.inclusivePct.toFixed(2)} |`
      );
});

lines.push('', `**Всего строк:** ${rows.length}`);

fs.writeFileSync(outPath, lines.join('\n'), 'utf8');
console.error(`Wrote ${rows.length} frames to ${outPath}`);
