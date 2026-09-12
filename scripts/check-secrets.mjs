#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const patterns = [
  ['cloud-access-id', /\b(?:AKID|AKIA|LTAI)[A-Za-z0-9]{16,}\b/g],
  ['private-key', /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\s\\nr]*[A-Za-z0-9+/]{64,}/g],
  ['literal-cloud-secret', /(?:accessSecret|secretKey|apiKey)[\\"']*\s*[:=]\s*[\\"']+([^\\"'\r\n${}]{16,})/g]
];
export function scan(text, filename) {
  const findings = [];
  for (const [rule, pattern] of patterns) {
    pattern.lastIndex = 0;
    for (const match of text.matchAll(pattern)) {
      const canaries = {
        '后端程序/yudao-server/src/test/java/cn/iocoder/yudao/server/SdkLoggingHardeningContractTest.java': '63d443563317f4c1454c3e50ff46ab4f6a27339233fbc5b28fa2d27bbbdfa895',
        '后端程序/yudao-server/src/test/java/cn/iocoder/yudao/server/wiring/SensitiveLoggingPolicyTest.java': 'a1029a94ad9a686f7897b7bacc76533bb0fef696a1dfb383f3efd6dd7a1f93a7'
      };
      // Only these two pre-existing synthetic log-leak canaries are allowlisted by exact digest and file.
      if (rule === 'cloud-access-id' && canaries[filename] === createHash('sha256').update(match[0]).digest('hex')) continue;
      if (rule === 'literal-cloud-secret' && /^(?:REPLACE_|CHANGE_ME|test-|stub-|fake-|fixture-|example-|local-|dev-)/i.test(match[1])) continue;
      // Report location only. Never print the matched value or neighboring source.
      findings.push({ file: filename, line: text.slice(0, match.index).split('\n').length, rule });
    }
  }
  return findings;
}
function walk(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    if (['.git', 'node_modules', '.claude', 'target', 'dist'].includes(entry.name)) return [];
    const full = path.join(directory, entry.name);
    return entry.isDirectory() ? walk(full) : [full];
  });
}
if (process.argv.includes('--self-test')) {
  assert.equal(scan('AK' + 'ID' + 'a'.repeat(32), 'fixture').length, 1);
  assert.equal(scan(JSON.stringify({ accessSecret: 'x'.repeat(32) }), 'fixture').length, 1);
  assert.equal(scan(JSON.stringify({ accessSecret: 'REPLACE_WITH_SECRET' }), 'fixture').length, 0);
  assert.equal(scan('secretKey: ${ZS_COS_SECRET_KEY}', 'fixture').length, 0);
  console.log('secret scanner self-test passed');
} else {
  const index = process.argv.indexOf('--root');
  const root = index >= 0 ? path.resolve(process.argv[index + 1] || '') : repository;
  const files = index >= 0 ? walk(root) : [...new Set(execFileSync('git', ['-c', `safe.directory=${repository.replaceAll('\\', '/')}`, 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], { cwd: repository, encoding: 'utf8' }).split('\0').filter(Boolean))].map(f => path.join(root, f));
  const findings = [];
  let scanned = 0;
  for (const file of files) {
    if (!fs.existsSync(file) || !/\.(?:sql|json|ya?ml|[cm]?js|ts|vue|java|xml|md|toml|env|properties|txt|ps1|sh)$/i.test(file) || fs.statSync(file).size > 8 * 1024 * 1024) continue;
    const bytes = fs.readFileSync(file); if (bytes.includes(0)) continue;
    scanned++; findings.push(...scan(bytes.toString('utf8'), path.relative(root, file).replaceAll('\\', '/')));
  }
  console.log(JSON.stringify({ scanned, findings }, null, 2));
  if (findings.length) process.exitCode = 1;
}
