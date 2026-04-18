/**
 * Markdown 渲染器（markdown-it + Prism.js）
 * 每次调用 render() 做完整渲染，由调用方通过 RAF 控制频率
 */
class MarkdownRenderer {
    constructor() {
        this._initMarkdownIt();
        this._initPrism();
    }

    _initMarkdownIt() {
        if (!window.markdownit) {
            console.warn('markdown-it not loaded');
            this.md = null;
            return;
        }
        this.md = window.markdownit({
            html: false,
            linkify: true,
            typographer: true,
            breaks: true,
            highlight: this._highlightCode.bind(this),
        });
    }

    _initPrism() {
        this.prism = window.Prism ?? null;
        if (!this.prism) console.warn('Prism.js not loaded');
    }

    _highlightCode(code, lang) {
        const safeLang = lang?.toLowerCase() ?? 'text';
        if (!this.prism) {
            return `<pre><code class="language-${safeLang}">${MarkdownRenderer.escapeHtml(code)}</code></pre>`;
        }
        const language = this.prism.languages[safeLang]
            ? safeLang
            : (this.prism.languages.plaintext ? 'plaintext' : 'text');
        try {
            const highlighted = this.prism.highlight(code, this.prism.languages[language], language);
            return `<pre class="language-${language}"><code class="language-${language}">${highlighted}</code></pre>`;
        } catch {
            return `<pre><code class="language-${language}">${MarkdownRenderer.escapeHtml(code)}</code></pre>`;
        }
    }

    /** 完整渲染 Markdown → HTML */
    render(text) {
        if (!text) return '';
        if (!this.md) return MarkdownRenderer.escapeHtml(text);
        try {
            return this.md.render(text);
        } catch (e) {
            console.error('Markdown render error:', e);
            return MarkdownRenderer.escapeHtml(text);
        }
    }

    static escapeHtml(text) {
        if (!text) return '';
        return String(text).replace(/[&<>"']/g,
            c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' }[c])
        );
    }

    static getInstance() {
        if (!MarkdownRenderer._instance) {
            MarkdownRenderer._instance = new MarkdownRenderer();
        }
        return MarkdownRenderer._instance;
    }
}

MarkdownRenderer._instance = null;

export default MarkdownRenderer;
