<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, deleteDoc, listDocs, uploadDoc, type Doc } from '../api'

const namespace = ref('company')
const docs = ref<Doc[]>([])
const error = ref('')
const notice = ref('')
const busy = ref(false)

const file = ref<File | null>(null)
const acl = ref('public')

async function refresh() {
  error.value = ''
  try {
    const r = await listDocs(namespace.value)
    docs.value = r.documents
  } catch (e) {
    error.value = e instanceof ApiError ? e.detail : '加载失败'
  }
}

function onFile(e: Event) {
  const input = e.target as HTMLInputElement
  file.value = input.files?.[0] ?? null
}

async function submit() {
  if (!file.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const r = await uploadDoc(file.value, namespace.value, acl.value)
    notice.value = `已入库：${r.document.title || r.document.doc_id}（该分区共 ${r.namespace_chunks} 块）`
    file.value = null
    await refresh()
  } catch (e) {
    // ★ 415/422 是 Python 侧的解析失败（格式不支持 / 文件损坏），原样透出
    error.value = e instanceof ApiError ? e.detail : '上传失败'
  } finally {
    busy.value = false
  }
}

async function remove(doc: Doc) {
  if (!confirm(`删除「${doc.title || doc.doc_id}」？`)) return
  error.value = ''
  try {
    await deleteDoc(namespace.value, doc.doc_id)
    await refresh()
  } catch (e) {
    error.value = e instanceof ApiError ? e.detail : '删除失败'
  }
}

onMounted(refresh)
</script>

<template>
  <section class="card">
    <h1>文档</h1>

    <div class="row">
      <label class="inline">
        分区
        <input v-model="namespace" @change="refresh" />
      </label>
      <button class="ghost" @click="refresh">刷新</button>
    </div>

    <table class="docs">
      <thead>
        <tr><th>标题</th><th>doc_id</th><th>ACL</th><th>版本</th><th></th></tr>
      </thead>
      <tbody>
        <tr v-for="d in docs" :key="d.doc_id">
          <td>{{ d.title || '（无标题）' }}</td>
          <td class="mono">{{ d.doc_id }}</td>
          <td>{{ (d.acl || []).join(', ') }}</td>
          <td>{{ d.version || '-' }}</td>
          <td><button class="link danger" @click="remove(d)">删除</button></td>
        </tr>
        <tr v-if="!docs.length">
          <td colspan="5" class="muted">这个分区还没有文档</td>
        </tr>
      </tbody>
    </table>

    <h2>上传</h2>
    <form class="upload" @submit.prevent="submit">
      <input type="file" accept=".md,.txt,.pdf,.docx,.html" @change="onFile" />
      <label class="inline">
        ACL
        <select v-model="acl">
          <option value="public">public（所有人可见）</option>
          <option value="hr">hr（仅人事）</option>
        </select>
      </label>
      <button type="submit" :disabled="!file || busy">{{ busy ? '上传中…' : '上传' }}</button>
    </form>

    <p v-if="notice" class="notice">{{ notice }}</p>
    <p v-if="error" class="error">{{ error }}</p>
  </section>
</template>
