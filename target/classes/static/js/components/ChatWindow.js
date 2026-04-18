/**
 * ChatWindow 组件
 * - 消息列表和 sessionId 来自共享 store，支持多会话
 * - 打字机效果：流式期间逐字显示纯文本，完成后渲染完整 Markdown
 * - 思考过程默认折叠，可点击展开
 * - textarea 输入框：Enter 发送，Shift+Enter 换行
 */
import {sendMessageAPI} from '../api.js';
import MarkdownRenderer from '../utils/MarkdownRenderer.js';
import SmartScroll from '../utils/SmartScroll.js';
import {
    currentSession,
    currentSessionId,
    isLoading as storeLoading,
    toggleMobileSidebar,
    updateSessionTitle,
} from '../store.js';

// ── 工具 ──────────────────────────────────────────────────────────────────────
const md = MarkdownRenderer.getInstance();
const renderMd = (text) => md.render(text);
const escapeHtml = MarkdownRenderer.escapeHtml;

// 打字机速度：约 40 字符/秒
const TW_INTERVAL = 25;  // ms
const TW_CHARS = 1;

// ── 组件 ──────────────────────────────────────────────────────────────────────
export const ChatWindow = {
    name: 'ChatWindow',
    template: `
    <div class="container">
        <div class="header">
            <button class="mobile-menu-btn" @click="toggleMobileSidebar" aria-label="打开会话列表">☰</button>
            <h1>AI 对话</h1>
        </div>

        <div class="chat-container" ref="chatEl" role="log" aria-label="聊天消息">
            <div class="chat-content">
                <div v-for="(msg, i) in messages" :key="i" class="message" :class="msg.role">

                    <!-- 用户消息 -->
                    <template v-if="msg.role === 'user'">
                        <div class="message-content" v-html="msg.html"></div>
                    </template>

                    <!-- AI 消息 -->
                    <template v-else>
                        <!-- 思考过程（可折叠） -->
                        <div v-if="msg.thinking" class="message-thinking">
                            <div class="thinking-header" @click="msg.thinkingExpanded = !msg.thinkingExpanded">
                                <span class="thinking-toggle">{{ msg.thinkingExpanded ? '▾' : '▸' }}</span>
                                <span class="thinking-label">思考过程</span>
                                <span class="thinking-chars">{{ msg.thinking.length }} 字</span>
                            </div>
                            <div v-show="msg.thinkingExpanded" class="thinking-body"
                                 aria-live="polite" aria-atomic="false">{{ msg.thinking }}</div>
                        </div>

                        <!-- 加载点（等待首字节） -->
                        <div v-if="msg.streaming && !msg.content && !msg.thinking" class="loading-indicator" aria-live="polite">
                            <span class="loading-dot"></span>
                            <span class="loading-dot"></span>
                            <span class="loading-dot"></span>
                        </div>

                        <!-- 消息正文 -->
                        <div v-if="msg.html"
                             class="message-text"
                             :class="{ streaming: msg.streaming }"
                             :aria-live="msg.streaming ? 'polite' : 'off'"
                             :aria-busy="msg.streaming"
                             v-html="msg.html">
                        </div>
                    </template>

                </div>
            </div>
        </div>

        <div class="input-container">
            <div class="input-wrapper">
                <!-- 思考模式开关 -->
                <button class="thinking-toggle-btn"
                        @click="thinkingEnabled = !thinkingEnabled"
                        :class="{ active: thinkingEnabled }"
                        :title="thinkingEnabled ? '点击关闭思考模式' : '点击开启思考模式'"
                        :disabled="loading">
                    💭
                </button>

                <textarea v-model="inputText"
                          @keydown="handleKeydown"
                          placeholder="输入你的问题…"
                          :disabled="loading"
                          rows="1"
                          ref="inputEl"
                          aria-label="消息输入框"></textarea>
                <button @click="loading ? stop() : send()"
                        :class="{ 'stop-btn': loading }"
                        :aria-label="loading ? '停止生成' : '发送消息'">
                    {{ loading ? '停止' : '发送' }}
                </button>
            </div>
        </div>
    </div>
    `,

    setup() {
        const inputText = Vue.ref('');
        const thinkingEnabled = Vue.ref(false);
        const chatEl = Vue.ref(null);
        const inputEl = Vue.ref(null);

        // 从 store 读取当前会话消息（响应式）
        const messages = Vue.computed(() => currentSession.value?.messages ?? []);
        // 本地 loading ref 同步写入 store
        const loading = Vue.computed({
            get: () => storeLoading.value,
            set: (v) => {
                storeLoading.value = v;
            },
        });

        let ctrl = null;
        let smartScroll = null;

        // ── 打字机状态（每次 send() 重建） ───────────────────────────────────
        let twTimerId = null;
        let twFullText = '';
        let twPos = 0;
        let twDone = false;
        let twAiMsg = null;

        // ── 生命周期 ────────────────────────────────────────────────────────
        Vue.onMounted(() => {
            smartScroll = new SmartScroll(chatEl.value);
            Vue.nextTick(() => inputEl.value?.focus());
        });

        Vue.onUnmounted(() => {
            smartScroll?.destroy();
            clearInterval(twTimerId);
        });

        // ── 切换会话时重置打字机状态 ──────────────────────────────────────────
        Vue.watch(currentSessionId, () => {
            if (loading.value) {
                ctrl?.abort();
                ctrl = null;
            }
            clearInterval(twTimerId);
            twTimerId = null;
            twFullText = '';
            twPos = 0;
            twDone = false;
            twAiMsg = null;
            loading.value = false;
            Vue.nextTick(() => {
                smartScroll?.forceScrollToBottom();
                inputEl.value?.focus();
            });
        });

        // ── 工具函数 ────────────────────────────────────────────────────────
        const scrollBottom = () => Vue.nextTick(() => smartScroll?.scrollToBottom());

        // ── 打字机：启动 ────────────────────────────────────────────────────
        const startTypewriter = (aiMsg) => {
            twAiMsg = aiMsg;
            if (twTimerId) return;
            twTimerId = setInterval(() => {
                if (twPos >= twFullText.length) {
                    if (twDone) {
                        clearInterval(twTimerId);
                        twTimerId = null;
                        twAiMsg.html = renderMd(twFullText);
                        twAiMsg.streaming = false;
                        loading.value = false;
                        ctrl = null;
                        Vue.nextTick(() => {
                            smartScroll?.scrollToBottom();
                            inputEl.value?.focus();
                        });
                    }
                    return;
                }
                twPos = Math.min(twPos + TW_CHARS, twFullText.length);
                twAiMsg.html = escapeHtml(twFullText.slice(0, twPos));
                scrollBottom();
            }, TW_INTERVAL);
        };

        // ── 打字机：停止 ────────────────────────────────────────────────────
        const stopTypewriter = () => {
            if (twTimerId) {
                clearInterval(twTimerId);
                twTimerId = null;
            }
            if (twAiMsg?.streaming) {
                twAiMsg.html = renderMd(twFullText.slice(0, twPos) || twFullText);
                twAiMsg.streaming = false;
            }
            twAiMsg = null;
        };

        // ── 发送消息 ────────────────────────────────────────────────────────
        const send = async () => {
            const text = inputText.value.trim();
            if (!text || loading.value) return;

            const sessionId = currentSessionId.value;
            const msgs = currentSession.value.messages;

            ctrl = new AbortController();
            inputText.value = '';
            loading.value = true;

            twFullText = '';
            twPos = 0;
            twDone = false;
            twAiMsg = null;
            if (twTimerId) {
                clearInterval(twTimerId);
                twTimerId = null;
            }

            msgs.push({
                role: 'user',
                content: text,
                thinking: '',
                thinkingExpanded: false,
                html: escapeHtml(text),
                streaming: false,
            });

            // 用首条用户消息更新会话标题
            updateSessionTitle(sessionId, text);

            const aiMsg = Vue.reactive({
                role: 'ai',
                content: '',
                thinking: '',
                thinkingExpanded: false,
                html: '',
                streaming: true,
            });
            msgs.push(aiMsg);

            Vue.nextTick(() => smartScroll?.forceScrollToBottom());

            await sendMessageAPI(text, sessionId, {
                signal: ctrl.signal,

                onThinking: (chunk) => {
                    if (!aiMsg.thinking) aiMsg.thinkingExpanded = true;
                    aiMsg.thinking += chunk;
                    scrollBottom();
                },

                onContent: (chunk) => {
                    if (!aiMsg.streaming) return;
                    twFullText += chunk;
                    aiMsg.content = twFullText;
                    startTypewriter(aiMsg);
                },

                onComplete: () => {
                    twDone = true;
                    if (!twTimerId && twPos >= twFullText.length) {
                        if (twFullText) aiMsg.html = renderMd(twFullText);
                        aiMsg.streaming = false;
                        loading.value = false;
                        ctrl = null;
                        Vue.nextTick(() => {
                            smartScroll?.scrollToBottom();
                            inputEl.value?.focus();
                        });
                    }
                },

                onError: (err) => {
                    clearInterval(twTimerId);
                    twTimerId = null;
                    const displayed = twFullText.slice(0, twPos);
                    aiMsg.html = displayed ? renderMd(displayed) : escapeHtml(`请求出错: ${err.message}`);
                    aiMsg.streaming = false;
                    loading.value = false;
                    ctrl = null;
                },
            }, thinkingEnabled.value);
        };

        // ── 停止生成 ────────────────────────────────────────────────────────
        const stop = () => {
            stopTypewriter();
            loading.value = false;
            ctrl?.abort();
            ctrl = null;
        };

        // ── 键盘处理 ────────────────────────────────────────────────────────
        const handleKeydown = (e) => {
            if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey) {
                e.preventDefault();
                send();
            } else if (e.key === 'Enter' && (e.shiftKey || e.ctrlKey)) {
                // 在 textarea 中插入换行，通过 Vue ref 保持响应式同步
                e.preventDefault();
                const pos = e.target.selectionStart;
                inputText.value = inputText.value.slice(0, pos) + '\n' + inputText.value.slice(pos);
                Vue.nextTick(() => {
                    e.target.selectionStart = e.target.selectionEnd = pos + 1;
                });
            } else if (e.key === 'Escape' && loading.value) {
                e.preventDefault();
                stop();
            }
        };

        return {
            messages,
            inputText,
            loading,
            thinkingEnabled,
            chatEl,
            inputEl,
            send,
            stop,
            handleKeydown,
            toggleMobileSidebar
        };
    },
};
