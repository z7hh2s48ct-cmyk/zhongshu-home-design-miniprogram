import type { Plugin } from 'vite'

export function editorSupplyChainGate(): Plugin {
  return {
    name: 'zs-editor-supply-chain-gate',
    generateBundle() {
      const ids = [...this.getModuleIds()].map(id => id.replaceAll('\\', '/'))
      const forbidden = ids.filter(id => /\/node_modules\/wangeditor\//.test(id)
        || /\/@form-create\/designer\/dist\//.test(id))
      if (forbidden.length) this.error('Build includes the removed legacy editor or its embedded designer bundle')
      this.emitFile({ type: 'asset', fileName: 'dependency-reachability.json', source: JSON.stringify({
        legacyEditorModules: forbidden.length,
        designerSource: ids.some(id => /\/@form-create\/designer\/src\/index.js/.test(id)),
        safeEditorBridge: ids.some(id => id.includes('/components/FormCreate/src/components/SafeEditor.vue'))
      }, null, 2) })
    }
  }
}
