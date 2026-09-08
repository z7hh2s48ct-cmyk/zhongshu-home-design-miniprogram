#!/usr/bin/env node

import { spawnSync } from 'node:child_process'
import { existsSync, readdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDirectory = dirname(fileURLToPath(import.meta.url))
const repositoryDirectory = resolve(scriptDirectory, '..')
const miniProgramDirectory = join(repositoryDirectory, '前端程序')
const adminDirectory = join(repositoryDirectory, '管理后台')
const backendDirectory = join(repositoryDirectory, '后端程序')
const requestedTarget = process.argv[2] ?? 'all'
const supportedTargets = new Set(['all', 'full', 'doctor', 'mini', 'admin', 'backend', 'e2e'])
const isWindows = process.platform === 'win32'

class VerificationError extends Error {
  constructor(stage, message) {
    super(message)
    this.stage = stage
  }
}

function fail(stage, message) {
  throw new VerificationError(stage, message)
}

function nativeCommand(name) {
  return isWindows ? `${name}.cmd` : name
}

function executableInvocation(command, args) {
  if (!isWindows || !command.toLowerCase().endsWith('.cmd')) {
    return { command, args }
  }
  const quote = (value) =>
    /[\s&|<>^()]/.test(value) ? `"${value.replaceAll('"', '""')}"` : value
  return {
    command: process.env.ComSpec ?? 'cmd.exe',
    args: ['/d', '/s', '/c', `call ${[command, ...args].map(quote).join(' ')}`],
  }
}

function runCaptured(stage, command, args, cwd = repositoryDirectory) {
  const invocation = executableInvocation(command, args)
  const result = spawnSync(invocation.command, invocation.args, {
    cwd,
    encoding: 'utf8',
    env: process.env,
    shell: false,
  })
  if (result.error) {
    fail(stage, `无法执行 ${command}：${result.error.message}`)
  }
  return result
}

function runStep(stage, label, command, args, cwd) {
  const startedAt = Date.now()
  console.log(`\n[${stage}] ${label}`)
  const invocation = executableInvocation(command, args)
  const result = spawnSync(invocation.command, invocation.args, {
    cwd,
    env: process.env,
    shell: false,
    stdio: 'inherit',
  })
  if (result.error) {
    fail(stage, `无法执行 ${command}：${result.error.message}`)
  }
  if (result.status !== 0) {
    fail(stage, `${label} 失败，退出码 ${result.status ?? 'unknown'}`)
  }
  console.log(`[${stage}] ${label} 通过（${((Date.now() - startedAt) / 1000).toFixed(1)}s）`)
}

function parseVersion(text) {
  const match = text.match(/(\d+)\.(\d+)\.(\d+)/)
  return match ? match.slice(1).map(Number) : null
}

function versionAtLeast(actual, minimum) {
  for (let index = 0; index < minimum.length; index += 1) {
    if (actual[index] > minimum[index]) return true
    if (actual[index] < minimum[index]) return false
  }
  return true
}

function checkNode() {
  const actual = parseVersion(process.version)
  if (!actual || !versionAtLeast(actual, [20, 19, 0])) {
    fail('doctor', `Node.js 版本不满足要求：当前 ${process.version}，需要 >= 20.19.0`)
  }
  console.log(`[doctor] Node.js ${process.version}：通过`)
}

function checkAdmin() {
  const stage = 'admin:preflight'
  const result = runCaptured(stage, nativeCommand('pnpm'), ['--version'])
  const version = result.stdout.trim()
  if (result.status !== 0 || version !== '11.19.0') {
    fail(stage, `pnpm 版本不满足要求：当前 ${version || '不可用'}，需要 11.19.0`)
  }
  if (!existsSync(join(adminDirectory, 'node_modules'))) {
    fail(stage, '管理端依赖未安装；请先在“管理后台”执行 pnpm install --frozen-lockfile')
  }
  console.log(`[doctor] pnpm ${version} 与管理端依赖：通过`)
}

function checkBackend() {
  const stage = 'backend:preflight'
  const java = runCaptured(stage, 'java', ['-version'], backendDirectory)
  const javaOutput = `${java.stdout}\n${java.stderr}`
  const javaMajor = javaOutput.match(/version "(?<major>\d+)/)?.groups?.major
  if (java.status !== 0 || javaMajor !== '17') {
    fail(stage, `JDK 版本不满足要求：当前 ${javaMajor ?? '不可用'}，需要 17`)
  }

  const wrapper = join(backendDirectory, isWindows ? 'mvnw.cmd' : 'mvnw')
  if (!existsSync(wrapper)) {
    fail(stage, `Maven Wrapper 不存在：${wrapper}`)
  }

  const docker = runCaptured(stage, 'docker', ['info', '--format', '{{.ServerVersion}}'])
  if (docker.status !== 0 || !docker.stdout.trim()) {
    fail(stage, 'Docker daemon 不可用；后端合同测试需要 Testcontainers')
  }
  console.log(`[doctor] JDK ${javaMajor}、Maven Wrapper、Docker ${docker.stdout.trim()}：通过`)
}

function printSensitiveEnvironmentPresence() {
  const names = [
    'ZS_PG_URL',
    'ZS_PG_USERNAME',
    'ZS_PG_PASSWORD',
    'ZS_INTERNAL_SECRET',
    'ZS_WX_APPID',
    'ZS_ACCESS_CODE_PEPPER',
    'ZS_ACCESS_CODE_ARTIFACT_KEY',
  ]
  const status = names.map((name) => `${name}=${process.env[name] ? '已设置' : '未设置'}`).join('，')
  console.log(`[doctor] 敏感环境变量仅报告状态：${status}`)
}

function doctor(target) {
  checkNode()
  if (target === 'all' || target === 'full' || target === 'admin') checkAdmin()
  if (target === 'all' || target === 'full' || target === 'backend' || target === 'e2e') checkBackend()
  printSensitiveEnvironmentPresence()
}

function verifyMiniProgram() {
  const testsDirectory = join(miniProgramDirectory, 'tests')
  const testFiles = readdirSync(testsDirectory)
    .filter((name) => name.endsWith('.test.js'))
    .sort()
    .map((name) => join(testsDirectory, name))
  if (testFiles.length === 0) fail('mini', '未找到 tests/*.test.js')
  runStep('mini', `${testFiles.length} 个测试文件`, process.execPath, ['--test', ...testFiles], miniProgramDirectory)
}

function verifyAdmin() {
  const pnpm = nativeCommand('pnpm')
  runStep('admin', 'TypeScript 类型检查', pnpm, ['ts:check'], adminDirectory)
  runStep('admin', 'ESLint / Stylelint / Prettier', pnpm, ['lint'], adminDirectory)
  runStep('admin', '生产构建', pnpm, ['build:prod'], adminDirectory)
}

function verifyBackend() {
  const wrapper = join(backendDirectory, isWindows ? 'mvnw.cmd' : 'mvnw')
  const modules = [
    'yudao-module-identity',
    'yudao-module-commerce',
    'yudao-module-ai-orchestration',
    'yudao-module-design',
  ].join(',')
  runStep(
    'backend',
    '17 个众墅合同测试文件',
    wrapper,
    [
      '-B',
      '-Dtest=*ContractTest',
      '-Dsurefire.failIfNoSpecifiedTests=false',
      '-pl',
      modules,
      '-am',
      'test',
    ],
    backendDirectory,
  )
}

function verifyE2E() {
  runStep('e2e', '37 个三端全链检查点', process.execPath, ['e2e/zs-e2e.mjs'], repositoryDirectory)
}

function usage() {
  console.log('用法：node scripts/verify.mjs [all|full|doctor|mini|admin|backend|e2e]')
}

try {
  if (!supportedTargets.has(requestedTarget)) {
    usage()
    fail('arguments', `不支持的目标：${requestedTarget}`)
  }

  const doctorTarget = requestedTarget === 'doctor' ? 'all' : requestedTarget
  doctor(doctorTarget)
  if (requestedTarget === 'doctor') process.exit(0)

  const stages = requestedTarget === 'all'
    ? ['mini', 'admin', 'backend']
    : requestedTarget === 'full'
      ? ['mini', 'admin', 'backend', 'e2e']
      : [requestedTarget]
  for (const stage of stages) {
    if (stage === 'mini') verifyMiniProgram()
    if (stage === 'admin') verifyAdmin()
    if (stage === 'backend') verifyBackend()
    if (stage === 'e2e') verifyE2E()
  }
  console.log(`\n[verify] ${stages.join(' -> ')} 全部通过`)
} catch (error) {
  const stage = error instanceof VerificationError ? error.stage : 'unexpected'
  console.error(`\n[verify] 失败阶段=${stage}：${error.message}`)
  process.exit(1)
}
