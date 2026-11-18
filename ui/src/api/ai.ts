import axios from 'axios'

export interface AiResult {
  content: string
}

export interface AiConfig {
  configured: boolean
  model: string
  modes: string[]
}

export const AI_BASE = '/apis/console.api.ihalo.run/v1alpha1/ai'

export type AiMode = 'write' | 'continue' | 'polish' | 'rewrite' | 'format'

export async function runAi(mode: AiMode, input: string, extra: string): Promise<string> {
  const { data } = await axios.post<AiResult>(AI_BASE, { mode, input, extra })
  return data.content
}

export async function fetchAiConfig(): Promise<AiConfig> {
  const { data } = await axios.get<AiConfig>(`${AI_BASE}/config`)
  return data
}
