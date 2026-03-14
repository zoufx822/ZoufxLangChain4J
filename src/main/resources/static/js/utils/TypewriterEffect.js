/**
 * 打字机效果工具类
 * 实现逐字显示效果，支持Markdown渲染和代码高亮
 */
class TypewriterEffect {
    /**
     * 构造函数
     * @param {Object} options - 配置选项
     */
    constructor(options = {}) {
        // 默认配置
        this.options = {
            speed: 30, // 每个字符的延迟时间（毫秒）
            chunkSize: 5, // 每次渲染的字符数（性能优化）
            cursorChar: '▌', // 光标字符
            cursorBlinkSpeed: 500, // 光标闪烁速度（毫秒）
            useMarkdown: true, // 是否使用Markdown渲染
            markdownRenderer: null, // Markdown渲染器实例
            onProgress: null, // 进度回调函数
            ...options
        };

        // 状态
        this.isTyping = false;
        this.currentAnimation = null;
        this.cursorInterval = null;
    }

    /**
     * 开始打字机效果
     * @param {HTMLElement} element - 要显示文本的HTML元素
     * @param {string} text - 要显示的文本
     * @param {Object} options - 覆盖默认选项
     * @returns {Promise} 打字完成时解析的Promise
     */
    async typewrite(element, text, options = {}) {
        // 如果正在打字，先停止
        await this.stop();

        // 合并选项
        const finalOptions = { ...this.options, ...options };
        const {
            speed,
            chunkSize,
            cursorChar,
            useMarkdown,
            markdownRenderer,
            onProgress
        } = finalOptions;

        // 设置打字状态
        this.isTyping = true;
        element.classList.add('typewriting');

        // 显示初始光标
        this._showCursor(element, cursorChar);

        try {
            // 逐块显示文本
            for (let i = 0; i < text.length; i += chunkSize) {
                // 检查是否应该停止
                if (!this.isTyping) break;

                // 获取当前要显示的文本
                const endIndex = Math.min(i + chunkSize, text.length);
                const displayedText = text.slice(0, endIndex);

                // 渲染文本
                let html;
                if (useMarkdown && markdownRenderer) {
                    // 使用Markdown渲染器进行增量渲染
                    html = markdownRenderer.renderIncremental(displayedText);
                } else {
                    // 纯文本（HTML转义）
                    html = this._escapeHtml(displayedText);
                }

                // 更新元素内容（包含光标）
                element.innerHTML = html + `<span class="typewriter-cursor">${cursorChar}</span>`;

                // 调用进度回调
                if (onProgress) {
                    onProgress({
                        current: endIndex,
                        total: text.length,
                        progress: endIndex / text.length,
                        displayedText,
                        html
                    });
                }

                // 等待一段时间
                await this._sleep(speed);
            }

            // 打字完成（如果未中途停止）
            if (this.isTyping) {
                await this._complete(element, text, finalOptions);
            }
        } catch (error) {
            console.error('Typewriter effect error:', error);
            // 出错时显示完整文本
            await this._complete(element, text, finalOptions, true);
        } finally {
            // 清理
            this.isTyping = false;
            element.classList.remove('typewriting');
        }
    }

    /**
     * 完成打字
     * @private
     */
    async _complete(element, text, options, force = false) {
        const { useMarkdown, markdownRenderer, cursorChar } = options;

        // 渲染完整文本
        let html;
        if (useMarkdown && markdownRenderer) {
            html = markdownRenderer.render(text);
        } else {
            html = this._escapeHtml(text);
        }

        // 更新元素内容（不带光标）
        element.innerHTML = html;

        // 添加完成动画类
        element.classList.add('typewriter-complete');

        // 短暂延迟后移除完成类
        setTimeout(() => {
            element.classList.remove('typewriter-complete');
        }, 300);

        // 等待一小段时间确保渲染完成
        await this._sleep(100);
    }

    /**
     * 显示光标
     * @private
     */
    _showCursor(element, cursorChar) {
        // 添加光标样式类
        element.classList.add('has-typewriter-cursor');

        // 创建光标元素
        const cursorElement = document.createElement('span');
        cursorElement.className = 'typewriter-cursor';
        cursorElement.textContent = cursorChar;

        // 如果元素为空，添加光标
        if (!element.innerHTML.trim()) {
            element.appendChild(cursorElement);
        }
    }

    /**
     * 隐藏光标
     * @private
     */
    _hideCursor(element) {
        element.classList.remove('has-typewriter-cursor');
        const cursor = element.querySelector('.typewriter-cursor');
        if (cursor) {
            cursor.remove();
        }
    }

    /**
     * 停止打字
     */
    async stop() {
        if (this.isTyping) {
            this.isTyping = false;

            // 等待当前动画完成
            if (this.currentAnimation) {
                this.currentAnimation.cancel();
            }

            // 清理光标
            if (this.cursorInterval) {
                clearInterval(this.cursorInterval);
                this.cursorInterval = null;
            }

            await this._sleep(50);
        }
    }

    /**
     * 立即完成打字（跳过动画）
     * @param {HTMLElement} element - HTML元素
     * @param {string} text - 完整文本
     */
    completeImmediately(element, text) {
        this.stop();

        const { useMarkdown, markdownRenderer } = this.options;

        let html;
        if (useMarkdown && markdownRenderer) {
            html = markdownRenderer.render(text);
        } else {
            html = this._escapeHtml(text);
        }

        element.innerHTML = html;
        this._hideCursor(element);
    }

    /**
     * 是否正在打字
     * @returns {boolean}
     */
    isRunning() {
        return this.isTyping;
    }

    /**
     * 睡眠函数
     * @private
     */
    _sleep(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    }

    /**
     * HTML转义
     * @private
     */
    _escapeHtml(text) {
        if (!text) return '';
        return String(text).replace(/[&<>"']/g,
            c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' }[c])
        );
    }

    /**
     * 更新配置
     * @param {Object} options - 新配置选项
     */
    updateOptions(options) {
        this.options = { ...this.options, ...options };
    }

    /**
     * 销毁实例，清理资源
     */
    destroy() {
        this.stop();
        this.options = null;
        this.currentAnimation = null;
    }
}

export default TypewriterEffect;