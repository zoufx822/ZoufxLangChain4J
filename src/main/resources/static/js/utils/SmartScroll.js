/**
 * 智能滚动工具类
 * 功能：智能管理聊天窗口滚动行为
 * - 默认自动滚动到最新消息
 * - 用户手动向上滚动时暂停自动滚动
 * - 滚动到底部附近时恢复自动滚动
 * - 支持平滑滚动和手动恢复
 */
class SmartScroll {
    /**
     * 构造函数
     * @param {HTMLElement} container - 滚动容器元素
     * @param {Object} options - 配置选项
     */
    constructor(container, options = {}) {
        this.container = container;

        // 默认配置
        this.options = {
            threshold: 150, // 距离底部多少像素内触发自动滚动
            autoScrollDelay: 2000, // 用户手动滚动后恢复自动滚动的延迟（毫秒）
            smoothScroll: true, // 是否使用平滑滚动
            ...options
        };

        // 状态
        this.isUserScrolling = false; // 用户是否正在手动滚动
        this.lastScrollTop = 0; // 上次滚动位置
        this.autoScrollTimer = null; // 自动滚动恢复计时器

        // 绑定事件处理函数
        this._handleScroll = this._handleScroll.bind(this);
        this._handleTouchStart = this._handleTouchStart.bind(this);
        this._handleWheel = this._handleWheel.bind(this);

        // 初始化事件监听
        this._initEventListeners();
    }

    /**
     * 初始化事件监听
     */
    _initEventListeners() {
        // 监听滚动事件
        this.container.addEventListener('scroll', this._handleScroll, { passive: true });

        // 监听触摸开始（移动端用户交互）
        this.container.addEventListener('touchstart', this._handleTouchStart, { passive: true });

        // 监听鼠标滚轮（用户交互）
        this.container.addEventListener('wheel', this._handleWheel, { passive: true });

        // 监听容器尺寸变化
        if (typeof ResizeObserver !== 'undefined') {
            this.resizeObserver = new ResizeObserver(() => {
                // 容器尺寸变化时，如果用户没有手动滚动，自动滚动到底部
                if (!this.isUserScrolling) {
                    this.scrollToBottom({ immediate: true });
                }
            });
            this.resizeObserver.observe(this.container);
        }
    }

    /**
     * 处理滚动事件
     */
    _handleScroll() {
        const scrollTop = this.container.scrollTop;
        const scrollHeight = this.container.scrollHeight;
        const clientHeight = this.container.clientHeight;

        // 检测滚动方向
        if (scrollTop < this.lastScrollTop) {
            // 向上滚动，视为用户手动干预
            this._setUserScrolling(true);
        }

        // 更新上次滚动位置
        this.lastScrollTop = scrollTop;

        // 计算距离底部的距离
        const distanceFromBottom = scrollHeight - scrollTop - clientHeight;

        // 如果滚动到底部附近，恢复自动滚动
        if (distanceFromBottom <= this.options.threshold) {
            this._setUserScrolling(false);
        }
    }

    /**
     * 处理触摸开始事件（移动端用户交互）
     */
    _handleTouchStart() {
        this._setUserScrolling(true);
    }

    /**
     * 处理鼠标滚轮事件（桌面端用户交互）
     */
    _handleWheel() {
        this._setUserScrolling(true);
    }

    /**
     * 设置用户滚动状态
     * @param {boolean} isScrolling - 是否用户正在滚动
     */
    _setUserScrolling(isScrolling) {
        if (this.isUserScrolling === isScrolling) return;

        this.isUserScrolling = isScrolling;

        // 清除之前的计时器
        if (this.autoScrollTimer) {
            clearTimeout(this.autoScrollTimer);
            this.autoScrollTimer = null;
        }

        // 如果用户停止滚动，设置计时器恢复自动滚动
        if (!isScrolling) {
            this.autoScrollTimer = setTimeout(() => {
                this.isUserScrolling = false;
                this.autoScrollTimer = null;
            }, this.options.autoScrollDelay);
        }
    }

    /**
     * 重置自动滚动状态
     */
    resetAutoScroll() {
        this._setUserScrolling(false);
    }

    /**
     * 检查是否应该自动滚动
     * @returns {boolean} 是否应该自动滚动
     */
    shouldAutoScroll() {
        return !this.isUserScrolling;
    }

    /**
     * 滚动到底部
     * @param {Object} options - 滚动选项
     * @param {string} options.behavior - 滚动行为 'smooth' 或 'auto'
     * @param {boolean} options.immediate - 是否立即滚动（忽略用户滚动状态）
     */
    scrollToBottom(options = {}) {
        const shouldAutoScroll = options.immediate || this.shouldAutoScroll();

        if (!shouldAutoScroll) {
            return false;
        }

        const scrollOptions = {
            top: this.container.scrollHeight,
            behavior: options.immediate ? 'auto' : (this.options.smoothScroll ? 'smooth' : 'auto')
        };

        // 使用 requestAnimationFrame 确保在下一帧滚动
        requestAnimationFrame(() => {
            try {
                this.container.scrollTo(scrollOptions);
            } catch (error) {
                // 降级方案：直接设置 scrollTop
                this.container.scrollTop = this.container.scrollHeight;
            }
        });

        return true;
    }

    /**
     * 计算距离底部的距离
     * @returns {number} 距离底部的像素数
     */
    getDistanceFromBottom() {
        const scrollTop = this.container.scrollTop;
        const scrollHeight = this.container.scrollHeight;
        const clientHeight = this.container.clientHeight;
        return scrollHeight - scrollTop - clientHeight;
    }

    /**
     * 判断是否在底部附近
     * @param {number} customThreshold - 自定义阈值（可选）
     * @returns {boolean} 是否在底部附近
     */
    isNearBottom(customThreshold = null) {
        const threshold = customThreshold !== null ? customThreshold : this.options.threshold;
        return this.getDistanceFromBottom() <= threshold;
    }

    /**
     * 强制滚动到底部（忽略用户滚动状态）
     */
    forceScrollToBottom() {
        this.scrollToBottom({ immediate: true });
    }

    /**
     * 销毁实例，清理事件监听和计时器
     */
    destroy() {
        // 移除事件监听
        this.container.removeEventListener('scroll', this._handleScroll);
        this.container.removeEventListener('touchstart', this._handleTouchStart);
        this.container.removeEventListener('wheel', this._handleWheel);

        // 清理 ResizeObserver
        if (this.resizeObserver) {
            this.resizeObserver.disconnect();
            this.resizeObserver = null;
        }

        // 清理计时器
        if (this.autoScrollTimer) {
            clearTimeout(this.autoScrollTimer);
            this.autoScrollTimer = null;
        }

        // 清理引用
        this.container = null;
    }
}

export default SmartScroll;