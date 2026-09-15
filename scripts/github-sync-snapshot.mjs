// Prepare an auditable GitHub connector upload without changing HEAD or the index.
// Generates only ignored local artifacts; makes no network requests.
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync, lstatSync } from 'node:fs'
import { resolve, relative, sep } from 'node:path'
import { TextDecoder } from 'node:util'

const root = resolve(import.meta.dirname, '..')
const output = resolve(root, 'artifacts/github-sync-20260915')
const git = (...args) => execFileSync('git', args, { cwd: root, maxBuffer: 16 * 1024 * 1024 })
const lines = (buffer) => buffer.toString('utf8').split('\0').filter(Boolean)
const statusBefore = git('status', '--porcelain=v1', '-z').toString('base64')
const headBefore = git('rev-parse', 'HEAD').toString('utf8').trim()
const index = resolve(root, git('rev-parse', '--git-path', 'index').toString('utf8').trim())
const hash = (data) => createHash('sha256').update(data).digest('hex')
const indexBefore = hash(readFileSync(index))
const tracked = new Map(lines(git('ls-files', '--stage', '-z')).map((line) => {
  const match = /^(\d+) ([0-9a-f]+) (\d)\t([\s\S]+)$/.exec(line)
  if (!match || match[3] !== '0' || !['100644', '100755'].includes(match[1])) {
    throw new Error('Unsupported Git entry or unresolved conflict')
  }
  return [match[4], match[1]]
}))
const files = [...new Set([...tracked.keys(), ...lines(git('ls-files', '--others', '--exclude-standard', '-z'))])].sort()
const denied = /(^|\/)(\.git|node_modules|target|dist|artifacts|archive|backups|coverage|\.venv|__pycache__|\.pytest_cache)(\/|$)|(^|\/)yaoming-rooms\.json$|\.(pem|key|p12|pfx|jks|keystore|pt|zip|7z|tar\.gz)$/i
const decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true })
const manifest = []
const payload = []
for (const path of files) {
  const file = resolve(root, path)
  const rel = relative(root, file)
  if (rel.startsWith(`..${sep}`) || rel === '..' || denied.test(path) || !lstatSync(file).isFile()) {
    throw new Error(`Unsafe upload candidate: ${path}`)
  }
  // Git applies the repository's existing .gitattributes (including exact artwork bytes).
  const sha = git('hash-object', '-w', `--path=${path}`, '--', path).toString('utf8').trim()
  const content = git('cat-file', 'blob', sha)
  if (content.length > 10 * 1024 * 1024) throw new Error(`Unexpected large file: ${path}`)
  const blobSha = createHash('sha1').update(`blob ${content.length}\0`).update(content).digest('hex')
  if (blobSha !== sha) throw new Error(`Git blob mismatch: ${path}`)
  const mode = tracked.get(path) ?? '100644'
  let text
  try { text = decoder.decode(content) } catch { text = undefined }
  if (text !== undefined && Buffer.from(text, 'utf8').equals(content) && !text.includes('\0')) {
    payload.push({ path, mode, type: 'blob', content: text })
  } else {
    payload.push({ path, mode, type: 'blob', binary: true, base64: content.toString('base64'), sha })
  }
  manifest.push({ path, mode, type: 'blob', sha, size: content.length })
}
if (git('rev-parse', 'HEAD').toString('utf8').trim() !== headBefore || hash(readFileSync(index)) !== indexBefore || git('status', '--porcelain=v1', '-z').toString('base64') !== statusBefore) {
  throw new Error('Workspace changed during snapshot; do not upload this capture')
}
mkdirSync(output, { recursive: true })
const batches = []
let batch = []
let batchBytes = 2
for (const entry of payload) {
  const size = Buffer.byteLength(JSON.stringify(entry), 'utf8') + 1
  if (batch.length && batchBytes + size > 180000) {
    batches.push(batch)
    batch = []
    batchBytes = 2
  }
  batch.push(entry)
  batchBytes += size
}
if (batch.length) batches.push(batch)
for (let i = 0; i < batches.length; i++) {
  writeFileSync(resolve(output, `batch-${String(i).padStart(3, '0')}.json`), JSON.stringify(batches[i]))
}
const summary = {
  createdAt: new Date().toISOString(), headBefore, indexBefore,
  fileCount: manifest.length, bytes: manifest.reduce((n, file) => n + file.size, 0),
  binaryCount: payload.filter((entry) => entry.binary).length,
  batches: batches.length, files: manifest,
}
writeFileSync(resolve(output, 'manifest.json'), JSON.stringify(summary, null, 2) + '\n')
console.log(JSON.stringify({ ...summary, files: undefined, output }, null, 2))
