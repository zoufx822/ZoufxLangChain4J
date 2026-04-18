/**
 * API 通信层
 */

const API_ENDPOINT = '/ai/chat';

/**
 * 解析 SSE 缓冲区，返回完整事件列表和剩余未处理数据
 * SSE 事件块以空行（\n\n 或 \r\n\r\n）分隔
 *
 * 改进版本 v3:
 * 1. 正确处理多行data累积：使用数组收集data行，然后join('\n')，避免多余换行符
 * 2. 智能转义换行符处理：区分转义序列\\n和真实换行符
 * 3. 过滤空data事件（心跳）
 * 4. 保留未完成块用于下一次解析
 * 5. 改进残留数据处理：只处理包含完整SSE事件的残留数据
 */
function parseSSE(buffer) {
    const items = [];
    // 改进分隔符处理：正确处理\r\n\r\n和\n\n，保留未完成的块
    const blocks = buffer.split(/\r?\n\r?\n/);
    const remaining = blocks.pop() ?? '';

    for (const block of blocks) {
        if (!block.trim()) continue;
        let event = 'content';
        const dataLines = [];

        for (const line of block.split(/\r?\n/)) {
            if (line.startsWith('event:')) {
                event = line.slice(6).trim();
            } else if (line.startsWith('data:')) {
                // 累积data行，不自动添加换行符，保留原始格式
                dataLines.push(line.slice(5));
            }
            // 忽略其他字段（如id:, retry:）
        }

        if (dataLines.length > 0) {
            // 连接data行，保留原始格式
            let data = dataLines.join('\n');

            // 智能处理转义换行符：区分转义序列\\n和真实换行符
            // 1. 如果数据包含真实换行符，假设后端已经正确处理
            // 2. 如果数据只包含转义序列而没有真实换行符，进行转换
            // 3. 避免双重转换（转换后可能产生新的转义序列）
            const hasEscapedNewline = data.includes('\\n');
            const hasRealNewline = data.includes('\n');

            if (hasEscapedNewline && !hasRealNewline) {
                // 只有转义序列没有真实换行时进行转换
                // 使用负向后顾断言避免转换已经转换过的序列
                data = data.replace(/(?<!\\)\\n/g, '\n');
            }

            // 只有当data不为空字符串时才推送事件（空data可能是心跳）
            if (data !== '') {
                items.push({ event, data });
            }
        }
    }

    return { items, remaining };
}

/**
 * 发送消息并通过回调接收 SSE 流式响应
 * @param {string} message
 * @param {string} sessionId
 * @param {{ onThinking, onContent, onComplete, onError, signal }} callbacks
 */
export async function sendMessageAPI(message, sessionId, callbacks, thinking = true) {
    const { onThinking, onContent, onComplete, onError, signal } = callbacks;

    try {
        const res = await fetch(API_ENDPOINT, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({prompt: message, sessionId, thinking}),
            signal,
        });

        if (!res.ok) throw new Error(`请求失败: ${res.status}`);

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const { items, remaining } = parseSSE(buffer);
            buffer = remaining;
            for (const { event, data } of items) {
                if (event === 'thinking') onThinking?.(data);
                else if (event === 'error') {
                    onError?.(new Error(data));
                    return;
                } else onContent?.(data);
            }
        }

        // 处理末尾残留数据（改进版本）
        // 只处理看起来像完整SSE事件的残留数据（包含event:或data:字段）
        if (buffer.trim()) {
            // 检查残留数据是否包含SSE字段
            const hasSSEFields = /^(event:|data:)/m.test(buffer);
            if (hasSSEFields) {
                // 尝试最后一次解析
                const { items } = parseSSE(buffer + '\n\n'); // 添加结束分隔符
                for (const { event, data } of items) {
                    if (event === 'thinking') onThinking?.(data);
                    else if (event === 'error') {
                        onError?.(new Error(data));
                        return;
                    } else onContent?.(data);
                }
            } else {
                // 如果不是SSE格式，可能是纯文本内容
                onContent?.(buffer.trim());
            }
        }

        onComplete?.();
    } catch (err) {
        // ── 增强错误处理 v2 ───────────────────────────────────────────────
        // 增强AbortError检测：检查多种AbortError表示形式
        const isAbortError = err.name === 'AbortError' ||
                           err.code === 20 ||
                           (err instanceof DOMException && err.name === 'AbortError');

        if (isAbortError) {
            onComplete?.();
            return;
        }

        // 错误分类和用户友好消息
        let userMessage = '请求出错';
        let debugInfo = err.message || '未知错误';

        if (err.name === 'TypeError' && err.message.includes('fetch')) {
            userMessage = '网络连接失败，请检查网络连接';
        } else if (err.name === 'SyntaxError') {
            userMessage = '服务器响应格式错误';
        } else if (err.message.includes('status')) {
            // 提取状态码
            const statusMatch = err.message.match(/\d+/);
            const status = statusMatch ? statusMatch[0] : '未知';
            if (status === '401' || status === '403') {
                userMessage = '认证失败，请刷新页面重试';
            } else if (status === '404') {
                userMessage = '请求的资源不存在';
            } else if (status === '429') {
                userMessage = '请求过于频繁，请稍后重试';
            } else if (parseInt(status, 10) >= 500) {
                userMessage = `服务器内部错误 (${status})`;
            } else {
                userMessage = `请求失败 (${status})`;
            }
        } else if (err.message.includes('network') || err.message.includes('Network')) {
            userMessage = '网络错误，请检查连接';
        }

        console.error(`[API] 请求失败: ${debugInfo}`, err);
        // 创建带有额外信息的错误对象
        const enhancedError = new Error(userMessage);
        enhancedError.originalError = err;
        enhancedError.debugInfo = debugInfo;
        onError?.(enhancedError);
    }
}
