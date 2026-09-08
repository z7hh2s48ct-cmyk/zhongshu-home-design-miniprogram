#!/usr/bin/env node
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const baselinePath = join(root, "项目文档", "test-baseline.json");
const text = (path) => readFileSync(path, "utf8");
const count = (source, pattern) => [...source.matchAll(pattern)].length;
const walk = (dir, suffix) =>
  readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    return entry.isDirectory()
      ? walk(path, suffix)
      : entry.name.endsWith(suffix)
        ? [path]
        : [];
  });

const miniFiles = walk(join(root, "前端程序", "tests"), ".test.js");
const modules = [
  "yudao-module-identity",
  "yudao-module-commerce",
  "yudao-module-ai-orchestration",
  "yudao-module-design",
];
const backendFiles = modules.flatMap((module) =>
  walk(join(root, "后端程序", module, "src", "test"), "ContractTest.java"),
);
const e2eList =
  text(join(root, "e2e", "zs-e2e.mjs")).match(
    /const names = \[([\s\S]*?)\n\]/,
  )?.[1] || "";
const actual = {
  mini: {
    executableFiles: miniFiles.length,
    declaredTestCalls: miniFiles.reduce(
      (n, p) => n + count(text(p), /\btest\s*\(/g),
      0,
    ),
  },
  backend: {
    contractFiles: backendFiles.length,
    testMethods: backendFiles.reduce(
      (n, p) => n + count(text(p), /@Test\b/g),
      0,
    ),
    parameterizedMethods: backendFiles.reduce(
      (n, p) => n + count(text(p), /@ParameterizedTest\b/g),
      0,
    ),
  },
  e2e: { checkpoints: count(e2eList, /'[^']+'/g) },
};
console.log(JSON.stringify(actual, null, 2));
if (process.argv.includes("--check")) {
  const expected = JSON.parse(text(baselinePath)).inventory;
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    console.error(`测试资产口径漂移；请确认变更后更新 ${baselinePath}`);
    process.exit(1);
  }
  console.log("[inventory] 与受控基线一致");
}
