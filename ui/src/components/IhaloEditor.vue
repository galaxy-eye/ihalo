<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { marked } from 'marked'
import type { Attachment } from '@halo-dev/api-client'
import { Toast } from '@halo-dev/components'
import { runAi, fetchAiConfig, type AiConfig, type AiMode } from '../api/ai'

const props = withDefaults(
  defineProps<{
    title: string
    raw: string
    content: string
    uploadImage?: (file: File) => Promise<Attachment>
  }>(),
  {
    title: '',
    raw: '',
    content: '',
    uploadImage: undefined,
  },
)

const emit = defineEmits<{
  (event: 'update:title', value: string): void
  (event: 'update:raw', value: string): void
  (event: 'update:content', value: string): void
  (event: 'update', value: string): void
}>()

const text = ref(props.raw)
const rendered = computed(() => (marked.parse(text.value || '') as string) || '')

const MODES: { value: AiMode; label: string; hint: string }[] = [
  { value: 'write', label: '✍️ 写文章', hint: '根据主题生成完整文章' },
  { value: 'continue', label: '➡️ 续写', hint: '顺着当前内容继续写' },
  { value: 'polish', label: '✨ 润色', hint: '优化语句表达' },
  { value: 'rewrite', label: '🔄 改写', hint: '改写当前内容' },
  { value: 'format', label: '📐 优化排版', hint: '优化 Markdown 结构' },
]

const aiMode = ref<AiMode>('write')
const aiPrompt = ref('')
const aiLoading = ref(false)
const aiConfig = ref<AiConfig | null>(null)
const title = ref(props.title)

async function refreshConfig() {
  try {
    aiConfig.value = await fetchAiConfig()
  } catch {
    aiConfig.value = null
  }
}

watch(
  () => props.raw,
  (v) => {
    if (v !== text.value) {
      text.value = v ?? ''
    }
  },
)

watch(
  () => props.title,
  (v) => {
    if (v !== title.value) {
      title.value = v ?? ''
    }
  },
)

function syncToParent(value: string) {
  emit('update:raw', value)
  emit('update:content', (marked.parse(value || '') as string) || '')
  emit('update', value)
}

function onInput(e: Event) {
  const value = (e.target as HTMLTextAreaElement).value
  text.value = value
  syncToParent(value)
}

function onTitleInput(e: Event) {
  const value = (e.target as HTMLInputElement).value
  title.value = value
  emit('update:title', value)
}

async function execute() {
  const mode = aiMode.value
  let input = ''
  let extra = ''
  if (mode === 'write') {
    input = aiPrompt.value.trim()
  } else {
    input = text.value.trim()
    extra = aiPrompt.value.trim()
  }
  if (mode === 'write' && !input) {
    Toast.warning('请输入文章主题')
    return
  }
  if (mode !== 'write' && !input) {
    Toast.warning('当前内容为空')
    return
  }
  aiLoading.value = true
  try {
    const result = await runAi(mode, input, extra)
    if (mode === 'continue') {
      syncToParent(text.value ? `${text.value}\n\n${result}` : result)
    } else {
      syncToParent(result)
    }
    Toast.success('生成完成')
  } catch (e) {
    const data = (e as any)?.response?.data
    Toast.error(data?.message || 'AI 调用失败，请检查插件配置')
  } finally {
    aiLoading.value = false
  }
}

onMounted(async () => {
  await refreshConfig()
  const textarea = textareaRef.value
  if (textarea) {
    textarea.addEventListener('paste', async (e: ClipboardEvent) => {
      const items = e.clipboardData?.items
      if (!items) {
        return
      }
      for (let i = 0; i < items.length; i++) {
        const item = items[i]
        if (!item || item.type.indexOf('image') === -1) {
          continue
        }
        const file = item.getAsFile()
        if (file && props.uploadImage) {
          const attachment = await props.uploadImage(file)
          const permalink = attachment.status?.permalink
          if (permalink) {
            syncToParent(
              `${text.value}\n![${attachment.spec.displayName}](${permalink})`,
            )
          }
        }
      }
    })
  }
})

const textareaRef = ref<HTMLTextAreaElement | null>(null)
</script>

<template>
  <div class="ihalo-editor">
    <div class="ihalo-toolbar">
      <div class="ihalo-toolbar-title">
        <span class="ihalo-logo">✨</span>
        <input
          class="ihalo-title-input"
          :value="title"
          placeholder="请输入文章标题"
          @input="onTitleInput"
        />
      </div>

      <div class="ihalo-ai">
        <div class="ihalo-ai-modes">
          <button
            v-for="m in MODES"
            :key="m.value"
            class="ihalo-mode"
            :class="{ active: aiMode === m.value }"
            :title="m.hint"
            @click="aiMode = m.value"
          >
            {{ m.label }}
          </button>
        </div>
        <div class="ihalo-ai-input">
          <input
            v-model="aiPrompt"
            class="ihalo-prompt"
            :placeholder="
              aiMode === 'write'
                ? '输入文章主题，例如：如何入门 Halo 插件开发'
                : '补充要求（可选）'
            "
          />
          <button class="ihalo-run" :disabled="aiLoading" @click="execute">
            {{ aiLoading ? '生成中…' : '生成' }}
          </button>
        </div>
        <div class="ihalo-ai-status">
          <span v-if="aiConfig && !aiConfig.configured" class="ihalo-tip">
            尚未配置 AI 服务，请在插件设置中填写。
          </span>
          <span v-else-if="aiConfig" class="ihalo-tip">
            模型：{{ aiConfig.model }}
          </span>
        </div>
      </div>
    </div>

    <div class="ihalo-body">
      <div class="ihalo-pane">
        <div class="ihalo-pane-label">Markdown</div>
        <textarea
          ref="textareaRef"
          class="ihalo-textarea"
          :value="text"
          placeholder="在这里开始写作，或使用上方的 AI 工具…"
          @input="onInput"
        ></textarea>
      </div>
      <div class="ihalo-pane">
        <div class="ihalo-pane-label">预览</div>
        <div class="ihalo-preview" v-html="rendered"></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ihalo-editor {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 480px;
}

.ihalo-toolbar {
  border-bottom: 1px solid #e5e7eb;
  padding: 12px 16px;
  background: #f9fafb;
}

.ihalo-toolbar-title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ihalo-logo {
  font-size: 20px;
}

.ihalo-title-input {
  flex: 1;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 15px;
  font-weight: 600;
  outline: none;
}

.ihalo-title-input:focus {
  border-color: #2563eb;
}

.ihalo-ai {
  margin-top: 12px;
}

.ihalo-ai-modes {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ihalo-mode {
  border: 1px solid #d1d5db;
  background: #fff;
  border-radius: 999px;
  padding: 6px 14px;
  font-size: 13px;
  cursor: pointer;
  color: #374151;
}

.ihalo-mode:hover {
  border-color: #2563eb;
  color: #2563eb;
}

.ihalo-mode.active {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
}

.ihalo-ai-input {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.ihalo-prompt {
  flex: 1;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 14px;
  outline: none;
}

.ihalo-run {
  border: none;
  background: #2563eb;
  color: #fff;
  border-radius: 6px;
  padding: 8px 20px;
  font-size: 14px;
  cursor: pointer;
}

.ihalo-run:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.ihalo-ai-status {
  margin-top: 8px;
  font-size: 12px;
}

.ihalo-tip {
  color: #6b7280;
}

.ihalo-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.ihalo-pane {
  flex: 1;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #e5e7eb;
}

.ihalo-pane:last-child {
  border-right: none;
}

.ihalo-pane-label {
  padding: 8px 16px;
  background: #f9fafb;
  border-bottom: 1px solid #e5e7eb;
  font-size: 12px;
  color: #6b7280;
}

.ihalo-textarea {
  flex: 1;
  border: none;
  resize: none;
  outline: none;
  padding: 16px;
  font-family: 'Monaco', 'Fira Code', monospace;
  font-size: 14px;
  line-height: 1.7;
  background: #fff;
}

.ihalo-preview {
  flex: 1;
  overflow: auto;
  padding: 16px;
  line-height: 1.7;
}

.ihalo-preview :deep(h1),
.ihalo-preview :deep(h2),
.ihalo-preview :deep(h3) {
  border-bottom: 1px solid #e5e7eb;
  padding-bottom: 4px;
}

.ihalo-preview :deep(code) {
  background: #f3f4f6;
  padding: 2px 4px;
  border-radius: 4px;
  font-size: 0.9em;
}

.ihalo-preview :deep(pre) {
  background: #1f2937;
  color: #f9fafb;
  padding: 12px;
  border-radius: 6px;
  overflow: auto;
}

.ihalo-preview :deep(pre code) {
  background: transparent;
  padding: 0;
}

.ihalo-preview :deep(blockquote) {
  border-left: 4px solid #d1d5db;
  margin: 0;
  padding-left: 12px;
  color: #4b5563;
}
</style>
