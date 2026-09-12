const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { JSDOM } = require('jsdom')
const DOMPurify = require('dompurify')(new JSDOM('').window)
const { parse, compileScript } = require('vue/compiler-sfc')

function component(props) {
  const filename = path.join(__dirname, '../src/components/FormCreate/src/components/SafeEditor.vue')
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const source = compileScript(descriptor, { id: 'safe-editor' }).content
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }, events = []; let mounted, unmount
  vm.runInNewContext(code, { module, exports: module.exports, ...vue, onMounted: fn => { mounted = fn }, onBeforeUnmount: fn => { unmount = fn },
    require(name) { if (name === 'vue') return vue; if (name === 'dompurify') return DOMPurify; if (name === '@/components/Editor') return { Editor: {} }; throw Error(name) }
  })
  return { state: module.exports.default.setup(props, { expose() {}, emit: (...args) => events.push(args) }), events,
    mounted: () => mounted(), unmount: () => unmount(), template: descriptor.template.content }
}
test('incoming saved HTML and editor updates remove active content using actual DOMPurify', () => {
  const app = component({ modelValue: '<p>建筑方案</p><img src=x onerror="alert(1)"><script>alert(2)</script><iframe src=x></iframe>' })
  assert.match(app.state.html.value, /建筑方案/)
  assert.doesNotMatch(app.state.html.value, /onerror|script|iframe/)
  app.state.update('<a href="javascript:alert(1)">方案</a><form><input></form>')
  assert.equal(app.events[0][0], 'update:modelValue')
  assert.doesNotMatch(app.events[0][1], /javascript|form|input/)
  assert.match(app.template, /:readonly="disabled"/)
})
test('legacy init bridge sanitizes set/get and does not initialize an unmounted component', async () => {
  let bridge, stored = '<p>ok</p>', disabled = false
  const editor = { getHtml: () => stored, setHtml: value => { stored = value }, enable: () => { disabled = false }, disable: () => { disabled = true } }
  const app = component({ init: value => { bridge = value } })
  app.state.editorRef.value = { getEditorRef: async () => editor }; await app.mounted()
  bridge.txt.html('<p onclick="bad()">方案</p>'); assert.equal(stored, '<p>方案</p>'); assert.equal(bridge.txt.html(), stored)
  bridge.disable(); assert.equal(disabled, true); bridge.enable(); assert.equal(disabled, false)
  let called = false; const disposed = component({ init: () => { called = true } })
  disposed.state.editorRef.value = { getEditorRef: async () => editor }; disposed.unmount(); await disposed.mounted(); assert.equal(called, false)
})
