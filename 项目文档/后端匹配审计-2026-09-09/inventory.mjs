// 只读源码盘点；仅在本目录写出报告，不调用业务接口或执行构建。
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
const reportDirectory = path.dirname(fileURLToPath(import.meta.url));
const out = process.argv.includes('--after') ? path.join(reportDirectory, '清理后') : reportDirectory;
fs.mkdirSync(out, { recursive: true });
const root = path.resolve(reportDirectory, '../..');
const backend = path.join(root, '后端程序');
const mini = path.join(root, '前端程序/miniprogram');
const skip = new Set(['node_modules', '.git', 'target', 'dist', '.idea', 'build']);
function files(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => skip.has(e.name) ? []
    : e.isDirectory() ? files(path.join(dir, e.name)) : [path.join(dir, e.name)]);
}
const rel = p => path.relative(root, p).replaceAll('\\', '/');
const read = p => fs.readFileSync(p, 'utf8');
const stripJava = s => s.replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ' '))
  .replace(/^\s*\/\/[^\n]*/gm, m => m.replace(/[^\n]/g, ' '));
const stripXml = s => s.replace(/<!--[\s\S]*?-->/g, '');
const lineAt = (s, i) => s.slice(0, i).split('\n').length;
const normalize = p => decodeURIComponent(p).replace(/\{[^}]*\}/g, '{}');
const modules = ['identity', 'design', 'commerce', 'ai-orchestration'];
const endpoints = [];
for (const module of modules) {
  for (const file of files(path.join(backend, 'yudao-module-' + module, 'src/main/java')).filter(p => p.endsWith('Controller.java'))) {
    const source = stripJava(read(file));
    const base = /@RequestMapping\(\s*"([^"]+)"\s*\)/.exec(source);
    if (!base) continue;
    const surface = file.includes(`${path.sep}app${path.sep}`) ? 'app' : file.includes(`${path.sep}admin${path.sep}`) ? 'admin' : 'internal';
    const prefix = surface === 'app' ? '/app-api' : surface === 'admin' ? '/admin-api' : '';
    for (const m of source.matchAll(/@(Get|Post|Put|Patch|Delete)Mapping\b(?:\(([^\n]*)\))?/g)) {
      const suffix = /"([^"]*)"/.exec(m[2] || '')?.[1] || '';
      endpoints.push({ module, surface, method: m[1].toUpperCase(), url: prefix + base[1] + suffix,
        source: rel(file), line: lineAt(source, m.index) });
    }
  }
}
const apiFile = path.join(mini, 'utils/api.js');
const apiSource = read(apiFile);
const captures = [];
let currentName;
const http = Object.fromEntries(['get', 'post', 'put', 'patch', 'del'].map(method => [method, (url) => {
  captures.push({ name: currentName, method: method === 'del' ? 'DELETE' : method.toUpperCase(), url: decodeURIComponent(url.split('?')[0]) });
}]));
const context = { module: { exports: {} }, require: p => { if (p !== './request') throw new Error('Unexpected require'); return http; } };
vm.runInNewContext(apiSource, context, { timeout: 1000 });
const miniFiles = files(mini).filter(p => p.endsWith('.js') && !p.includes('tdesign-miniprogram') && p !== apiFile);
for (const [name, fn] of Object.entries(context.module.exports)) {
  currentName = name;
  const params = /function\s*\(([^)]*)\)/.exec(fn.toString())[1].split(',').map(s => s.trim()).filter(Boolean);
  fn(...params.map(p => ['params', 'options', 'input', 'body', 'config', 'data'].includes(p) ? {} : '{' + p + '}'));
  const item = captures[captures.length - 1];
  item.line = lineAt(apiSource, apiSource.indexOf(name + ': function'));
  item.callSites = miniFiles.flatMap(p => [...read(p).matchAll(new RegExp('\\bapi\\.' + name + '\\b', 'g'))]
    .map(m => ({ file: rel(p), line: lineAt(read(p), m.index) })));
  const match = endpoints.find(e => e.method === item.method && normalize(e.url) === normalize(item.url));
  item.backend = match ? `${match.source}:${match.line}` : null;
}
for (const e of endpoints) {
  e.miniWrappers = captures.filter(c => c.method === e.method && normalize(c.url) === normalize(e.url)).map(c => c.name);
}
const rootPom = stripXml(read(path.join(backend, 'pom.xml')));
const activeTop = [...rootPom.matchAll(/<module>([^<]+)<\/module>/g)].map(m => m[1]);
const moduleStats = fs.readdirSync(backend).filter(n => n.startsWith('yudao-module-')).map(name => {
  const list = files(path.join(backend, name));
  const java = list.filter(p => p.endsWith('.java') && p.includes(`${path.sep}src${path.sep}main${path.sep}`));
  const tests = list.filter(p => p.endsWith('.java') && p.includes(`${path.sep}src${path.sep}test${path.sep}`));
  return { name, inRootReactor: activeTop.includes(name), javaFiles: java.length,
    javaLines: java.reduce((n, p) => n + read(p).split('\n').length, 0), testFiles: tests.length,
    sourceBytes: java.reduce((n, p) => n + fs.statSync(p).size, 0) };
});
const poms = files(backend).filter(p => path.basename(p) === 'pom.xml');
const dependencies = [];
for (const pom of poms) {
  const text = stripXml(read(pom)).replace(/<dependencyManagement>[\s\S]*?<\/dependencyManagement>/g, '');
  for (const d of text.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)) {
    const artifact = /<artifactId>([^<]+)<\/artifactId>/.exec(d[1])?.[1];
    if (artifact?.startsWith('yudao-')) dependencies.push({ pom: rel(pom), artifact,
      scope: /<scope>([^<]+)<\/scope>/.exec(d[1])?.[1] || 'compile' });
  }
}
const activePoms = [];
function visitPom(pom) {
  activePoms.push(rel(pom));
  for (const m of stripXml(read(pom)).matchAll(/<module>([^<]+)<\/module>/g)) visitPom(path.resolve(path.dirname(pom), m[1], 'pom.xml'));
}
visitPom(path.join(backend, 'pom.xml'));
const inactive = moduleStats.filter(m => !m.inRootReactor);
const inactiveTotals = inactive.reduce((a, m) => ({ modules: a.modules + 1, javaFiles: a.javaFiles + m.javaFiles,
  javaLines: a.javaLines + m.javaLines, sourceBytes: a.sourceBytes + m.sourceBytes }), { modules: 0, javaFiles: 0, javaLines: 0, sourceBytes: 0 });
const auditedFiles = [...new Set([...modules.flatMap(m => files(path.join(backend, 'yudao-module-' + m))),
  ...files(path.join(backend, 'yudao-server/src/main/java')), ...miniFiles, apiFile, path.join(mini, 'app.json'),
  ...poms, ...files(path.join(root, '管理后台/src/api/zs'))])].filter(p => /\.(java|js|ts|sql|xml|json)$/.test(p));
const sourceHashes = auditedFiles.map(p => ({ path: rel(p), sha256: crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex') }));
const data = { date: '2026-09-09', scannedAt: new Date().toISOString(), basis: '当前工作区（含未提交文件）；静态路由与字面引用扫描，不等于运行时覆盖',
  pageCount: JSON.parse(read(path.join(mini, 'app.json'))).pages.length,
  activeTop, activePoms, inactiveTotals, moduleStats, dependencies, endpoints, miniApi: captures, sourceHashes };
fs.writeFileSync(path.join(out, 'inventory.json'), JSON.stringify(data, null, 2) + '\n');
const md = ['# 接口与模块盘点附录', '', '> 生成命令：`node 项目文档/后端匹配审计-2026-09-09/inventory.mjs`。按当前工作区扫描；参数类型、鉴权、状态与字段语义需结合主报告人工核对。未找到调用不等于可删除。', '',
  `扫描时间（UTC）：${data.scannedAt}；页面 ${data.pageCount}，Maven reactor POM ${activePoms.length}，未启用模块 ${inactiveTotals.modules}，其 Java 主源码 ${inactiveTotals.javaFiles} 文件 / ${inactiveTotals.javaLines} 行（含注释和空行）。`, '',
  '## 小程序 API 封装与后端路由', '', '> 引用数也包含函数作为值放入分发表的形式。素材 content 走直连；不统计 E2E/测试为页面接入。', '', '| API 方法 | HTTP 路由 | 源码引用处数 | 后端声明 |', '|---|---|---:|---|',
  ...captures.map(c => `| ${c.name} | ${c.method} ${c.url} | ${c.callSites.length} | ${c.backend || '**未匹配**'} |`), '',
  '## 没有 api.js 封装的 C 端路由', '', '> content 路由通过素材上传/下载代码直接访问，需单独核对；其他路由按“待接入”或“待裁定”处理。', '',
  '| HTTP 路由 | 后端声明 |', '|---|---|',
  ...endpoints.filter(e => e.surface === 'app' && !e.miniWrappers.length).map(e => `| ${e.method} ${e.url} | ${e.source}:${e.line} |`), '',
  '## 模块规模（Java main 源码，非运行包体）', '', '| 模块 | 根 POM 启用 | Java 文件 | Java 行数（含注释/空行） | 测试文件 |', '|---|---|---:|---:|---:|',
  ...moduleStats.map(m => `| ${m.name} | ${m.inRootReactor ? '是' : '否'} | ${m.javaFiles} | ${m.javaLines} | ${m.testFiles} |`), '',
  '## 全部众墅后端路由', '', '| 端 | 方法与路径 | 文件与行号 |', '|---|---|---|',
  ...endpoints.map(e => `| ${e.surface} | ${e.method} ${e.url} | ${e.source}:${e.line} |`), ''];
fs.writeFileSync(path.join(out, '接口与模块盘点.md'), md.join('\n'));
console.log(JSON.stringify({ pages: data.pageCount, endpoints: Object.fromEntries(['app', 'admin', 'internal'].map(s => [s, endpoints.filter(e => e.surface === s).length])),
  miniWrappers: captures.length, unmatchedWrappers: captures.filter(c => !c.backend), unusedWrappers: captures.filter(c => !c.callSites.length).map(c => c.name),
  unwrapped: endpoints.filter(e => e.surface === 'app' && !e.miniWrappers.length).map(e => `${e.method} ${e.url}`),
  inactiveTotals, activePomCount: activePoms.length,
  activeToInactiveModuleDeps: dependencies.filter(d => activePoms.includes(d.pom) && inactive.some(m => m.name === d.artifact)) }, null, 2));
