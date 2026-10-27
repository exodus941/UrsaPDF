#!/usr/bin/env node
/* Syntax guard.
 *
 * One trap has cost this project six separate incidents, in two forms:
 *
 *   LOUD — a template literal ends early and what follows parses as code.
 *   Usually a syntax error, but the build reports it at a line nowhere near
 *   the cause, so the search starts in the wrong file.
 *
 *   SILENT — a backtick inside a CSS comment inside a template literal. The
 *   literal closes at that backtick, the rest of the stylesheet becomes
 *   expressions, and the whole thing can still be valid JavaScript. The build
 *   goes green, the app renders nothing, and the error surfaces somewhere
 *   unrelated. That one took the longest to find every single time.
 *
 * The structural fix for the stylesheets was to move them into real .css
 * files, and the suite asserts they stay there. This catches everything else,
 * including the newest variant: a script of mine generating a template literal
 * through a shell, where the shell ate the escapes.
 *
 * Layer one parses every source file with esbuild — the same parser the build
 * uses, so anything it rejects would have broken the build anyway, just later
 * and further from the cause.
 *
 * Layer two looks for the silent form directly: a template literal containing
 * a block comment that never closes inside that literal. That is the exact
 * signature. The comment opened, a stray backtick ended the string, and the
 * `*​/` was left outside.
 *
 * Run: npm run check
 */
import fs from 'node:fs'
import path from 'node:path'

/* esbuild is optional. Where a project has it — anything built with Vite,
   esbuild or tsup does — layer one runs and catches every syntax error.
   Where it does not, layer two still runs on its own with no dependencies at
   all, and that is the layer that finds the silent form anyway. */
import { createRequire } from 'node:module'

/* Resolve esbuild from the project being checked, not from wherever this file
   happens to live. A shared copy sitting outside any project would otherwise
   never find it and would silently drop to half its coverage. */
let transform = null
for (const from of [process.cwd() + '/', import.meta.url]) {
  try { ({ transform } = createRequire(from)('esbuild')); break } catch { /* keep looking */ }
}

/* Scan whatever you point it at, or the current directory. */
const ROOTS = process.argv.slice(2).length ? process.argv.slice(2) : ['.']
const SKIP = new Set(['node_modules', '.git', 'dist', 'build', 'out', '.next', 'coverage', 'vendor'])
const EXT = { '.js': 'js', '.jsx': 'jsx', '.mjs': 'js', '.ts': 'ts', '.tsx': 'tsx' }

const walk = dir => {
  let out = []
  let entries
  try { entries = fs.readdirSync(dir, { withFileTypes: true }) } catch { return out }
  for (const e of entries) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) { if (!SKIP.has(e.name) && !e.name.startsWith('.')) out = out.concat(walk(p)) }
    else if (EXT[path.extname(e.name)]) out.push(p)
  }
  return out
}

/* ── Layer two: the silent form ──
 *
 * A hand-rolled scanner rather than an AST walk, because by the time a parser
 * is involved the damage has already changed what the tokens are. This reads
 * the file the way the JavaScript lexer does — tracking which construct it is
 * inside — and reports template literals that swallowed an unclosed comment.
 */
function danglingCommentsInTemplates(src) {
  const hits = []
  let i = 0, line = 1
  /* Template nesting: each entry is a literal we are inside, remembering
     where it started and what it has accumulated. `${}` can contain another
     template, so this is a stack rather than a flag. */
  const stack = []
  const brace = []

  /* `/*` is only a comment opener where a comment could start: at the
     beginning, or after whitespace or punctuation. Anywhere else it is part of
     something else, and treating it as a comment produced false positives on
     the first run — a URL ending `example.com/*` and a glob `src/**​/*` both
     tripped it. Neither is a comment and neither is a bug. */
  const opensAComment = (body, at) => at === 0 || /[\s;,{}()[\]=:]/.test(body[at - 1])

  const finish = t => {
    for (let at = t.body.lastIndexOf('/*'); at !== -1; at = t.body.lastIndexOf('/*', at - 1)) {
      if (!opensAComment(t.body, at)) continue
      if (t.body.indexOf('*/', at) !== -1) return   // this comment closes; earlier ones did too
      hits.push({ line: t.line, snippet: t.body.slice(Math.max(0, at - 20), at + 60).replace(/\s+/g, ' ') })
      return
    }
  }

  while (i < src.length) {
    const c = src[i], n = src[i + 1]
    if (c === '\n') line++

    if (stack.length === 0) {
      /* Outside any template: skip over the things that can hide a backtick. */
      if (c === '/' && n === '/') { while (i < src.length && src[i] !== '\n') i++; continue }
      if (c === '/' && n === '*') {
        i += 2
        while (i < src.length && !(src[i] === '*' && src[i + 1] === '/')) { if (src[i] === '\n') line++; i++ }
        i += 2; continue
      }
      if (c === '"' || c === "'") {
        const q = c; i++
        while (i < src.length && src[i] !== q) { if (src[i] === '\\') i++; if (src[i] === '\n') line++; i++ }
        i++; continue
      }
      if (c === '`') { stack.push({ line, body: '' }); i++; continue }
      i++; continue
    }

    /* Inside a template literal. */
    const top = stack[stack.length - 1]
    if (c === '\\') { top.body += src.slice(i, i + 2); i += 2; continue }
    if (c === '$' && n === '{') { brace.push(stack.length); i += 2; top.body += '${'; continue }
    if (c === '}' && brace.length && brace[brace.length - 1] === stack.length) { brace.pop(); i++; top.body += '}'; continue }
    if (c === '`' && brace.length === 0) { finish(stack.pop()); i++; continue }
    if (c === '`' && brace.length) { stack.push({ line, body: '' }); i++; continue }
    top.body += c
    i++
  }
  /* An unterminated template is its own bug; esbuild will say so. */
  return hits
}

/* Layer three: a JSX comment that closes itself early.
 *
 * A JSX comment is a brace, a block comment, a brace. The block comment ends at
 * the FIRST closing sequence inside it, and the author almost always meant the
 * last one. Write the closing sequence in prose — explaining the syntax, which
 * is exactly when it happens — and the comment ends there. Everything after it
 * is no longer a comment. It becomes page text, and the app renders a paragraph
 * of source commentary to the user in both panes.
 *
 * Nothing else catches it. The code stays syntactically valid, so the parser in
 * layer one is happy, the build is green, and the only symptom is prose on
 * screen. Same family as the backtick in a CSS comment that layer two finds:
 * name a delimiter inside the thing it delimits and the thing ends early.
 *
 * Finding it took two attempts, and the first one is worth recording because it
 * was the more obvious rule and it was wrong. I checked whether a comment's
 * close was braced straight away. It always is: the closing sequence the author
 * typed in prose came WITH its brace, because they were quoting the whole JSX
 * comment form. So the comment looked perfectly terminated, one sentence early.
 *
 * The signature that does work is what comes AFTER. A comment ends, and then
 * the JSX text that follows still contains another closing sequence — which
 * only happens when the author meant to close there instead. Real JSX between
 * the two clears the file: an element or an expression means the first close
 * was deliberate. */
function selfClosingJsxComments(src) {
  const hits = []
  const OPEN = '{' + '/' + '*'
  const CLOSE = '*' + '/'
  let i = 0
  while (i < src.length) {
    const start = src.indexOf(OPEN, i)
    if (start < 0) break
    const end = src.indexOf(CLOSE, start + OPEN.length)
    if (end < 0) break
    const after = end + CLOSE.length + (src[end + CLOSE.length] === '}' ? 1 : 0)
    /* How far the orphaned text runs: up to the next real JSX, or the next
       stray close, whichever comes first. */
    const tail = src.slice(after, after + 600)
    const nextJsx = tail.search(/[<{]/)
    const strayClose = tail.indexOf(CLOSE)
    if (strayClose >= 0 && (nextJsx < 0 || strayClose < nextJsx)) {
      const line = src.slice(0, end).split('\n').length
      hits.push({ line, snippet: src.slice(Math.max(0, end - 40), end + 40).replace(/\s+/g, ' ') })
    }
    i = after
  }
  return hits
}

/* Layer four: a file that declares itself backtick-free, checked.
 *
 * Layer two catches a block comment left open inside a template literal, which
 * is one shape of the backtick trap. It is not the only shape, and the other
 * one has now cost five incidents in this project.
 *
 * The other shape: a comment inside a CSS template literal that names a
 * selector in backticks. The comment opens and closes correctly, so layer two
 * sees nothing wrong. But the FIRST backtick ended the template literal, and
 * every line after it parses as JavaScript. Sometimes that throws at build
 * time. Sometimes it is valid — ".dmd .row.page-actions" becomes a property
 * access, the build goes green, and the app dies at runtime with "cannot read
 * properties of undefined (reading page)", which points nowhere near the file.
 *
 * A general rule cannot know which template literals hold CSS. So this is
 * opt-in: a file says so in a marker comment, and then the rule is absolute —
 * between the opening backtick of that literal and the end of the file, the
 * only backtick allowed is the one that closes it.
 *
 * The marker is the words NO, BACKTICKS, BELOW and RETURN joined by hyphens.
 * It is spelled out nowhere in this file, and the strings below are built from
 * fragments, because a guard that contains its own trigger flags itself — this
 * one did, 38 times, on the commit that introduced it. A detector must not
 * match its own source. */
const MARKER = ['NO', 'BACKTICKS', 'BELOW', 'RETURN'].join('-')
const RETURN_LITERAL = 'return ' + String.fromCharCode(96)
function strayBackticksInDeclaredFile (src) {
  if (!src.includes(MARKER)) return []
  const open = src.indexOf(RETURN_LITERAL)
  if (open < 0) return []
  const start = open + 'return '.length
  const hits = []
  let last = -1
  for (let i = start + 1; i < src.length; i++) if (src[i] === '`') { hits.push(i); last = i }
  /* The final one closes the literal and is expected. */
  return hits.filter(i => i !== last).map(i => ({
    line: src.slice(0, i).split('\n').length,
    snippet: src.slice(Math.max(0, i - 50), i + 30).replace(/\s+/g, ' '),
  }))
}

const files = ROOTS.flatMap(walk)
let bad = 0

for (const f of files) {
  const src = fs.readFileSync(f, 'utf8')
  const loader = EXT[path.extname(f)]

  /* Layer one. */
  try {
    if (transform) await transform(src, { loader, format: 'esm' })
  } catch (err) {
    bad++
    for (const e of err.errors ?? [{ text: err.message }]) {
      const at = e.location ? `:${e.location.line}:${e.location.column}` : ''
      console.log(`\n  SYNTAX  ${f}${at}\n          ${e.text}`)
      if (e.location?.lineText) console.log(`          ${e.location.lineText.trim().slice(0, 100)}`)
    }
  }

  /* Layer two. */
  for (const h of danglingCommentsInTemplates(src)) {
    bad++
    console.log(`\n  TEMPLATE  ${f}:${h.line}`)
    console.log(`            A block comment opens inside a template literal and never closes inside it.`)
    console.log(`            This is what a stray backtick in a CSS comment looks like.`)
    console.log(`            …${h.snippet}…`)
  }

  /* Layer four. */
  for (const h of strayBackticksInDeclaredFile(src)) {
    bad++
    console.log(`\n  BACKTICK  ${f}:${h.line}`)
    console.log(`            This file declares itself backtick-free below its return.`)
    console.log(`            This one ends the template literal early. Everything after`)
    console.log(`            it parses as JavaScript, and the build may still go green.`)
    console.log(`            …${h.snippet}…`)
  }

  /* Layer three, JSX only. */
  if (loader === 'jsx' || loader === 'tsx') {
    for (const h of selfClosingJsxComments(src)) {
      bad++
      console.log(`\n  JSXCOMMENT  ${f}:${h.line}`)
      console.log(`              This JSX comment ends here, and the author kept writing.`)
      console.log(`              Everything after this point renders as page text.`)
      console.log(`              Describe comment syntax in words. Never type the delimiter.`)
      console.log(`              …${h.snippet}…`)
    }
  }
}

if (bad) {
  console.log(`\n${bad} problem${bad === 1 ? '' : 's'} across ${files.length} files.\n`)
  process.exit(1)
}
console.log(`syntax guard: ${files.length} files clean${transform ? '' : ' (parser layer skipped — no esbuild here)'}`)
