/**
 * ChatWindow 组件
 * - 纯 Vue 响应式，无直接 DOM 操作
 * - 打字机效果：流式期间逐字显示纯文本，完成后渲染完整 Markdown
 * - 思考过程默认折叠，可点击展开
 * - 支持思考模式开关
 */
import {sendMessageAPI} from '../api.js';
import MarkdownRenderer from '../utils/MarkdownRenderer.js';
import SmartScroll from '../utils/SmartScroll.js';

// ── Session ID ────────────────────────────────────────────────────────────────
const SESSION_KEY = 'aiChatSessionId';
const sessionId = sessionStorage.getItem(SESSION_KEY) ?? (() => {
    const id = crypto.randomUUID();
    sessionStorage.setItem(SESSION_KEY, id);
    return id;
})();

// ── 工具 ──────────────────────────────────────────────────────────────────────
const md = MarkdownRenderer.getInstance();
const renderMd = (text) => md.render(text);
const escapeHtml = MarkdownRenderer.escapeHtml;

const WELCOME = '你好！我是 AI 助手，有什么可以帮助你的吗？';

// 打字机速度：约 40 字符/秒（参考 Claude/GPT/Gemini 体感）
const TW_INTERVAL = 25;  // ms
const TW_CHARS = 1;   // 每帧字符数

// ── 组件 ──────────────────────────────────────────────────────────────────────
export const ChatWindow = {
    name: 'ChatWindow',
    template: `
    <div class="container">
        <div class="header">
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
                        <div v-if="msg.thinking" class="message-thinking" :class="{ collapsed: !msg.thinkingExpanded }">
                            <div class="thinking-header" @click="msg.thinkingExpanded = !msg.thinkingExpanded">
                                <span class="thinking-toggle">{{ msg.thinkingExpanded ? '▾' : '▸' }}</span>
                                <span class="thinking-label">思考过程</span>
                                <span class="thinking-chars">{{ msg.thinking.length }} 字</span>
                            </div>
                            <div v-show="msg.thinkingExpanded" class="thinking-body">{{ msg.thinking }}</div>
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
                        :disabled="isLoading">
                    💭
                </button>

                <input v-model="inputText"
                       @keydown="handleKeydown"
                       placeholder="输入你的问题..."
                       :disabled="isLoading"
                       type="text"
                       aria-label="消息输入框">
                <button @click="isLoading ? stop() : send()"
                        :class="{ 'stop-btn': isLoading }"
                        :aria-label="isLoading ? '停止生成' : '发送消息'">
                    {{ isLoading ? '停止' : '发送' }}
                </button>
            </div>
        </div>
    </div>
    `,

    setup() {
        const messages = Vue.ref([
            {
                role: 'ai',
                content: WELCOME,
                thinking: '',
                thinkingExpanded: false,
                html: renderMd(WELCOME),
                streaming: false
            },
        ]);
        const inputText = Vue.ref('');
        const isLoading = Vue.ref(false);
        const thinkingEnabled = Vue.ref(false);
        const chatEl = Vue.ref(null);

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
            Vue.nextTick(() => focusInput());
        });

        Vue.onUnmounted(() => {
            smartScroll?.destroy();
            clearInterval(twTimerId);
        });

        // ── 工具函数 ────────────────────────────────────────────────────────
        const focusInput = () => document.querySelector('.input-wrapper input')?.focus();
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
                        isLoading.value = false;
                        ctrl = null;
                        Vue.nextTick(() => {
                            smartScroll?.scrollToBottom();
                            focusInput();
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
            if (!text || isLoading.value) return;

            ctrl = new AbortController();
            inputText.value = '';
            isLoading.value = true;

            twFullText = '';
            twPos = 0;
            twDone = false;
            twAiMsg = null;
            if (twTimerId) {
                clearInterval(twTimerId);
                twTimerId = null;
            }

            messages.value.push({
                role: 'user',
                content: text,
                thinking: '',
                thinkingExpanded: false,
                html: escapeHtml(text),
                streaming: false
            });

            const aiMsg = Vue.reactive({
                role: 'ai',
                content: '',
                thinking: '',
                thinkingExpanded: false,
                html: '',
                streaming: true
            });
            messages.value.push(aiMsg);

            Vue.nextTick(() => smartScroll?.forceScrollToBottom());

            await sendMessageAPI(text, sessionId, {
                signal: ctrl.signal,

                onThinking: (chunk) => {
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
                        isLoading.value = false;
                        ctrl = null;
                        Vue.nextTick(() => {
                            smartScroll?.scrollToBottom();
                            focusInput();
                        });
                    }
                },

                onError: (err) => {
                    clearInterval(twTimerId);
                    twTimerId = null;
                    const displayed = twFullText.slice(0, twPos);
                    aiMsg.html = displayed ? renderMd(displayed) : escapeHtml(`请求出错: ${err.message}`);
                    aiMsg.streaming = false;
                    isLoading.value = false;
                    ctrl = null;
                },
            }, thinkingEnabled.value);
        };

        // ── 停止生成 ────────────────────────────────────────────────────────
        const stop = () => {
            stopTypewriter();
            isLoading.value = false;
            ctrl?.abort();
            ctrl = null;
        };

        // ── 键盘处理 ────────────────────────────────────────────────────────
        const handleKeydown = (e) => {
            if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey) {
                e.preventDefault();
                send();
            } else if (e.key === 'Enter' && (e.shiftKey || e.ctrlKey)) {
                e.preventDefault();
                const el = e.target;
                const pos = el.selectionStart;
                el.value = el.value.slice(0, pos) + '\n' + el.value.slice(pos);
                el.selectionStart = el.selectionEnd = pos + 1;
            } else if (e.key === 'Escape' && isLoading.value) {
                e.preventDefault();
                stop();
            }
        };

        return {messages, inputText, isLoading, thinkingEnabled, chatEl, send, stop, handleKeydown};
    },
};
