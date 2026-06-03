/**
 * Expands async-profiler prefix-compressed cpool[] in an HTML flame graph
 * (same algorithm as unpack() in the viewer). Removes unpack(cpool) and the
 * unpack function.
 *
 * Usage: node scripts/unpack-flame-cpool.mjs <input.html> [output.html]
 */
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const inPath = path.resolve(process.argv[2] || '');
const outPath = path.resolve(process.argv[3] || inPath);

if (!inPath) {
	console.error('Usage: node scripts/unpack-flame-cpool.mjs <input.html> [output.html]');
	process.exit(1);
}

function findArrayCloseBracket(src, openPos) {
	let depth = 0;
	let i = openPos;
	let inSingle = false;
	let inDouble = false;
	while (i < src.length) {
		const c = src[i];
		if (inSingle) {
			if (c === '\\' && i + 1 < src.length) {
				i += 2;
				continue;
			}
			if (c === "'") inSingle = false;
			i++;
			continue;
		}
		if (inDouble) {
			if (c === '\\' && i + 1 < src.length) {
				i += 2;
				continue;
			}
			if (c === '"') inDouble = false;
			i++;
			continue;
		}
		if (c === "'") {
			inSingle = true;
			i++;
			continue;
		}
		if (c === '"') {
			inDouble = true;
			i++;
			continue;
		}
		if (c === '[') depth++;
		else if (c === ']') {
			depth--;
			if (depth === 0) return i;
		}
		i++;
	}
	throw new Error('Unclosed cpool array');
}

function unpackCpool(cpool) {
	for (let i = 1; i < cpool.length; i++) {
		const prev = cpool[i - 1];
		const cur = cpool[i];
		const n = cur.charCodeAt(0) - 32;
		cpool[i] = prev.substring(0, n) + cur.substring(1);
	}
}

function removeUnpackFunction(html) {
	const needle = 'function unpack(cpool) {';
	const fn = html.indexOf(needle);
	if (fn === -1) return html;
	let lineStart = fn;
	while (lineStart > 0 && html[lineStart - 1] !== '\n') lineStart--;
	const open = html.indexOf('{', fn);
	let depth = 0;
	for (let j = open; j < html.length; j++) {
		const ch = html[j];
		if (ch === '{') depth++;
		else if (ch === '}') {
			depth--;
			if (depth === 0) {
				let end = j + 1;
				if (end < html.length && html[end] === '\r') end++;
				if (end < html.length && html[end] === '\n') end++;
				return html.slice(0, lineStart) + html.slice(end);
			}
		}
	}
	return html;
}

const html = fs.readFileSync(inPath, 'utf8');
const marker = 'const cpool = ';
const mi = html.indexOf(marker);
if (mi === -1) {
	console.error('No const cpool = found');
	process.exit(1);
}

const bracket = html.indexOf('[', mi);
if (bracket === -1) {
	console.error('No [ after const cpool =');
	process.exit(1);
}

const closeBracket = findArrayCloseBracket(html, bracket);
let semi = closeBracket + 1;
while (semi < html.length && /\s/.test(html[semi])) semi++;
if (html[semi] !== ';') {
	console.error('Expected ; after cpool ]');
	process.exit(1);
}
const arrayEnd = semi + 1;

const literal = html.slice(bracket, closeBracket + 1);
let cpool;
try {
	cpool = vm.runInNewContext('(' + literal + ')', Object.create(null));
} catch (e) {
	console.error('Failed to eval cpool literal:', e.message);
	process.exit(1);
}
if (!Array.isArray(cpool)) {
	console.error('cpool is not an array');
	process.exit(1);
}

unpackCpool(cpool);

const lines = cpool.map((s) => '\t' + JSON.stringify(s));
const newDecl = marker + '[\n' + lines.join(',\n') + '\n];';

let rest = html.slice(0, mi) + newDecl + html.slice(arrayEnd);
rest = rest.replace(/\r?\nunpack\s*\(\s*cpool\s*\)\s*;\s*\r?\n/, '\n');
rest = removeUnpackFunction(rest);

fs.writeFileSync(outPath, rest, 'utf8');
console.error(`Unpacked ${cpool.length} strings → ${outPath}`);
