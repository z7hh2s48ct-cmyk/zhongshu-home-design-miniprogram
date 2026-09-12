<script setup lang="ts">
import { Editor } from '@/components/Editor'
import DOMPurify from 'dompurify'
defineOptions({ name: 'fcEditor', inheritAttrs: false })
const props = defineProps<{
  modelValue?: string
  disabled?: boolean
  config?: { placeholder?: string; height?: number }
  init?: (editor: unknown) => void
}>()
const emit = defineEmits(['update:modelValue'])
const editorRef = ref<InstanceType<typeof Editor>>()
const sanitize = (html: string) =>
  DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['iframe', 'form', 'input']
  })
const html = computed(() => sanitize(props.modelValue || ''))
const update = (value: string) => emit('update:modelValue', sanitize(value))
let disposed = false
onBeforeUnmount(() => {
  disposed = true
})
onMounted(async () => {
  const editor = await editorRef.value?.getEditorRef()
  if (!editor || !props.init || disposed) return
  // Legacy initialization callbacks retain basic content/read-only operations, never the removed engine.
  props.init({
    txt: {
      html: (value?: string) =>
        value === undefined ? sanitize(editor.getHtml()) : editor.setHtml(sanitize(value))
    },
    enable: () => editor.enable(),
    disable: () => editor.disable()
  })
})
</script>
<template>
  <Editor
    ref="editorRef"
    v-bind="$attrs"
    :model-value="html"
    :readonly="disabled"
    :height="config?.height || 300"
    :editor-config="{ placeholder: config?.placeholder || '请输入内容...' }"
    @update:model-value="update"
  />
</template>
