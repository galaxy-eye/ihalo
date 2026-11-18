import { definePlugin } from '@halo-dev/ui-shared'
import { markRaw } from 'vue'
import IhaloEditor from './components/IhaloEditor.vue'

export default definePlugin({
  extensionPoints: {
    'editor:create': () => {
      return [
        {
          name: 'ihalo-ai',
          displayName: 'ihalo AI 编辑器',
          component: markRaw(IhaloEditor),
          rawType: 'markdown',
        },
      ]
    },
  },
})
