const { createApp, ref, reactive, computed, watch, onMounted, onUnmounted, nextTick } = Vue;

// API 配置
const API_BASE = '/lingframe/dashboard';

createApp({
    setup() {
        // ==================== 状态 ====================
        const lings = ref([]);
        const activeId = ref(null);
        const activeNav = ref('overview');
        const lingSearch = ref('');
        const canaryPct = ref(0);
        const isAuto = ref(false);
        const ipcEnabled = ref(true);
        const ipcTarget = ref('user-ling');
        const logs = ref([]);
        const lastAudit = ref(null);
        const logViewMode = ref('current');
        const logContainer = ref(null);
        const isUserScrolling = ref(false);
        const logPaused = ref(false);
        const logPausedBuffer = []; // 暂停期间缓存的日志（上限 500 条）
        const sidebarOpen = ref(false);
        const currentEnv = ref('dev');
        const currentTime = ref('');
        const sseStatus = ref('disconnected');
        const toasts = ref([]);
        const appState = reactive({ readonly: false });

        const currentTheme = ref(localStorage.getItem('lingframe_theme') || 'dark');
        const packages = ref([]);
        const packageSearch = ref('');
        const consoleExpanded = ref(false);
        const hasNewTraceAlert = ref(false);
        const globalLogContainer = ref(null);

        const stats = reactive({ total: 0, v1: 0, v2: 0, v1Pct: 0, v2Pct: 0, active: 0 });

        const loading = reactive({
            lings: false,
            status: false,
            canary: false,
            permissions: false,
            invocation: false,
            stats: false,
            simulate: false
        });

        const modal = reactive({
            show: false,
            title: '',
            message: '',
            actionText: '',
            loading: false,
            showVersionSelect: false,
            versions: [],
            selectedVersion: '',
            versionSelectLabel: '',
            showDeleteFileOption: false,
            deleteFile: false,
            onConfirm: null,
            isDanger: false,
            confirmInput: '',
            expectedConfirmInput: ''
        });

        const uninstallResultModal = reactive({
            show: false,
            title: '',
            message: '',
            result: null
        });

        const uploadModal = reactive({
            show: false,
            file: null,
            isDragging: false,
            uploading: false,
            progress: 0
        });

        // 时间线模态框
        const timelineModal = reactive({
            show: false,
            loading: false,
            selectedLingId: '',
            events: []
        });

        const envLabels = { dev: '开发', test: '测试', prod: '生产' };

        // 性能监控数据
        const perfMetrics = reactive({
            cpu: 0,
            processCpuLoad: 0,
            memory: 0,
            memoryUsed: 0,
            memoryTotal: 0,
            heapUsed: 0,
            heapMax: 0,
            heapUsage: 0,
            metaspaceUsed: 0,
            metaspaceMax: 0,
            metaspaceUsage: 0,
            loadedClassCount: 0,
            totalLoadedClassCount: 0,
            unloadedClassCount: 0,
            threads: 0,
            daemonThreads: 0,
            peakThreads: 0,
            gcCount: 0,
            gcTimeMs: 0,
            availableProcessors: 0,
            systemLoadAverage: 0
        });

        // 前值记录（用于趋势箭头）
        const prevMetrics = { cpu: 0, heapUsage: 0, metaspaceUsage: 0, threads: 0, gcCount: 0, gcTimeMs: 0, loadedClassCount: 0 };

        // 趋势箭头：返回 'up' / 'down' / 'stable'
        const getTrend = (key) => {
            const prev = prevMetrics[key];
            const curr = perfMetrics[key];
            if (prev === curr) return 'stable';
            return curr > prev ? 'up' : 'down';
        };

        // JVM 基础信息
        const jvmInfo = reactive({
            version: '',
            vendor: '',
            osName: '',
            osArch: '',
            uptimeMs: 0,
            pid: ''
        });

        // 性能历史数据（折线图用，普通对象避免响应式开销）
        const chartTimeRange = ref('30m');

        // 加载历史指标数据（从 SQLite 后端查询）
        const loadHistoryMetrics = async () => {
            const rangeMap = {
                '5m': { ms: 300000, interval: 0 },
                '15m': { ms: 900000, interval: 0 },
                '30m': { ms: 1800000, interval: 0 },
                '1h': { ms: 3600000, interval: 0 },
                '3h': { ms: 10800000, interval: 0 },
                'today': { ms: null, interval: 60, start: new Date().setHours(0, 0, 0, 0) },
                'yesterday': { ms: null, interval: 120, start: new Date(Date.now() - 86400000).setHours(0, 0, 0, 0), end: new Date(Date.now() - 86400000).setHours(23, 59, 59, 999) },
                '7d': { ms: null, interval: 300, start: Date.now() - 7 * 86400000 }
            };
            const range = rangeMap[chartTimeRange.value];
            if (!range) return;

            try {
                let start = range.start;
                let end = range.end;
                if (range.ms) {
                    end = Date.now();
                    start = end - range.ms;
                }
                const data = await api.get(`/metrics/history?start=${start}&end=${end}&interval=${range.interval}`);
                if (data && data.length > 0) {
                    // 清空现有历史数据，用后端数据替换
                    perfHistory.timestamps = data.map(d => d.bucket || d.timestamp);
                    perfHistory.cpu = data.map(d => d.cpu_usage);
                    perfHistory.heapUsage = data.map(d => d.heap_usage);
                    perfHistory.metaspaceUsage = data.map(d => d.metaspace_usage);
                    perfHistory.threads = data.map(d => d.thread_count);
                    perfHistory.gcCount = data.map(d => d.delta_gc_count || d.gc_count);
                    perfHistory.gcTimeMs = data.map(d => d.delta_gc_time_ms || d.gc_time_ms);
                    perfHistory.loadedClassCount = data.map(d => d.loaded_class_count);
                    destroyCharts();
                    nextTick(() => drawMonitorCharts());
                }
            } catch (e) {
                // 历史查询失败不影响实时数据展示
                console.warn('Failed to load historical metrics:', e);
            }
        };
        const perfHistory = {
            timestamps: [],
            cpu: [],
            heapUsage: [],
            metaspaceUsage: [],
            threads: [],
            gcCount: [],
            gcTimeMs: [],
            loadedClassCount: []
        };
        const MAX_HISTORY_POINTS = 3600; // 最多保留 3600 个数据点（3秒间隔约3小时）

        // Chart.js 实例缓存
        const chartInstances = {};

        // 监控图表配置
        const monitorCharts = computed(() => [
            { key: 'cpu', label: t('performance.cpu'), color: '#22c55e', isPercent: true },
            { key: 'heapUsage', label: t('performance.heap'), color: '#a855f7', isPercent: true },
            { key: 'metaspaceUsage', label: t('performance.metaspace'), color: '#10b981', isPercent: true },
            { key: 'threads', label: t('performance.threads'), color: '#06b6d4', isPercent: false },
            { key: 'gcCount', label: t('performance.gc'), color: '#ec4899', isPercent: false },
            { key: 'gcTimeMs', label: t('monitor.gcTime'), color: '#f97316', isPercent: false },
            { key: 'loadedClassCount', label: t('monitor.classCount'), color: '#f59e0b', isPercent: false }
        ]);

        // 灵元健康指标
        const lingHealthMetrics = reactive({});
        const lingGovernanceMetrics = reactive({});
        const runtimeDiagnostics = reactive({});
        const runtimeGovernanceReadiness = reactive({
            status: 'UNKNOWN',
            summary: '',
            sharedApiBoundaryFrozen: false,
            diagnosticsCount: 0,
            blockers: [],
            warnings: []
        });

        const invocationForm = reactive({
            timeoutMs: '',
            rateLimitPerSecond: '',
            maxConcurrentThreads: '',
            retryCount: '',
            fallbackValue: '',
            cpuBudgetMsPerMinute: '',
            memoryBudgetMb: ''
        });

        // 灵元首次出现时间记录（用于计算运行时长）
        const lingFirstSeen = reactive({});

        // 灵元服务数量缓存（从 playground 数据获取）
        const lingServiceCounts = reactive({});

        // 灵元星图状态
        const starMapCanvas = ref(null);
        const starMapHover = ref(null);
        let starMapAnimFrame = null;

        let eventSource = null;
        let timeTimer = null;
        let stressTimer = null;
        let perfTimer = null;
        let healthTimer = null;
        let governanceTimer = null;
        let diagnosticsTimer = null;
        let readinessTimer = null;
        let logIdCounter = 0;
        let toastIdCounter = 0;
        let pendingUninstallToastResult = null;

        // 日志筛选和聚合相关
        const logAggregationMode = ref(false);
        const logFilters = reactive({
            version: '',
            eventType: '',
            keyword: '',
            level: ''
        });

        const consoleHeight = ref(280);
        const autoScrollLogs = ref(true);
        const isResizingConsole = ref(false);

        const handleConsoleResize = (e) => {
            if (!isResizingConsole.value) return;
            const newHeight = window.innerHeight - e.clientY;
            if (newHeight >= 100 && newHeight <= window.innerHeight * 0.8) {
                consoleHeight.value = newHeight;
            }
        };

        const stopConsoleResize = () => {
            isResizingConsole.value = false;
            document.documentElement.classList.remove('resizing');
            document.removeEventListener('mousemove', handleConsoleResize);
            document.removeEventListener('mouseup', stopConsoleResize);
        };

        const startConsoleResize = (e) => {
            e.preventDefault();
            isResizingConsole.value = true;
            document.documentElement.classList.add('resizing');
            document.addEventListener('mousemove', handleConsoleResize);
            document.addEventListener('mouseup', stopConsoleResize);
        };

        // ==================== 计算属性 ====================
        const activeLing = computed(() => lings.value.find(p => p.lingId === activeId.value));
        const filteredLings = computed(() => {
            const q = lingSearch.value.trim().toLowerCase();
            if (!q) return lings.value;
            return lings.value.filter(p => p.lingId.toLowerCase().includes(q));
        });
        const canCanary = computed(() => (activeLing.value?.versionDetails?.length || 0) >= 2);
        const canOperate = computed(() => activeLing.value?.status === 'ACTIVE' || activeLing.value?.status === 'DEGRADED');
        const canActivate = computed(() => activeLing.value?.status === 'INACTIVE');
        const canDeactivate = computed(() => activeLing.value?.status === 'ACTIVE' || activeLing.value?.status === 'DEGRADED');
        const canRecover = computed(() => activeLing.value?.status === 'DEGRADED');
        const activeLingHealth = computed(() => activeId.value ? lingHealthMetrics[activeId.value]?.summary || null : null);
        const activeLingVersionHealth = computed(() => {
            if (!activeId.value || !activeLing.value?.versionDetails) {
                return [];
            }
            const versionMetrics = lingHealthMetrics[activeId.value]?.versions || {};
            return activeLing.value.versionDetails.map(versionInfo => ({
                ...versionInfo,
                metrics: versionMetrics[versionInfo.version] || null
            }));
        });
        const activeLingGovernance = computed(() => activeId.value ? lingGovernanceMetrics[activeId.value]?.summary || null : null);
        const activeLingVersionGovernance = computed(() => {
            if (!activeId.value || !activeLing.value?.versionDetails) {
                return [];
            }
            const versionMetrics = lingGovernanceMetrics[activeId.value]?.versions || {};
            return activeLing.value.versionDetails.map(versionInfo => ({
                ...versionInfo,
                metrics: versionMetrics[versionInfo.version] || null
            }));
        });
        const runtimeDiagnosticsList = computed(() => Object.values(runtimeDiagnostics));
        const onboardingSteps = computed(() => [
            { id: 1, key: 'step1', done: packages.value.length > 0 },
            { id: 2, key: 'step2', done: lings.value.length > 0 },
            { id: 3, key: 'step3', done: lings.value.some(p => p.status === 'ACTIVE') }
        ]);
        const filteredPackages = computed(() => {
            const q = packageSearch.value.trim().toLowerCase();
            if (!q) return packages.value;
            return packages.value.filter(pkg =>
                pkg.fileName.toLowerCase().includes(q) ||
                pkg.lingId.toLowerCase().includes(q)
            );
        });
        const sseStatusText = computed(() => ({
            connected: t('sidebar.sseConnected'),
            connecting: t('sidebar.sseConnecting'),
            disconnected: t('sidebar.sseDisconnected')
        }[sseStatus.value]));

        // 获取可用的版本列表
        const availableVersions = computed(() => {
            const versions = new Set();
            lings.value.forEach(ling => {
                if (ling.versionDetails) {
                    ling.versionDetails.forEach(v => {
                        versions.add(v.version);
                    });
                }
            });
            return Array.from(versions).sort();
        });

        // 筛选和聚合日志
        const displayLogs = computed(() => {
            let filteredLogs = logs.value;

            // 按视图模式筛选
            if (logViewMode.value === 'current' && activeId.value) {
                filteredLogs = filteredLogs.filter(l => l.lingId === activeId.value);
            } else if (logViewMode.value !== 'all' && logViewMode.value !== 'current') {
                filteredLogs = filteredLogs.filter(l => l.lingId === logViewMode.value);
            }

            // 按版本筛选
            if (logFilters.version) {
                filteredLogs = filteredLogs.filter(l => l.version === logFilters.version);
            }

            // 按事件类型筛选
            if (logFilters.eventType) {
                filteredLogs = filteredLogs.filter(l => l.type === logFilters.eventType);
            }

            // 按级别筛选
            if (logFilters.level) {
                filteredLogs = filteredLogs.filter(l => l.type === logFilters.level);
            }

            // 按关键词筛选
            if (logFilters.keyword) {
                const keyword = logFilters.keyword.toLowerCase();
                filteredLogs = filteredLogs.filter(l =>
                    l.content.toLowerCase().includes(keyword) ||
                    l.lingId.toLowerCase().includes(keyword) ||
                    (l.traceId && l.traceId.toLowerCase().includes(keyword))
                );
            }

            // 聚合模式
            if (logAggregationMode.value) {
                return aggregateLogs(filteredLogs);
            }

            return filteredLogs;
        });

        // ==================== Toast 通知 ====================
        const showToast = (message, type = 'info') => {
            const toastMessage = pendingUninstallToastResult && type === 'success'
                ? buildUninstallToastMessage(pendingUninstallToastResult, message)
                : message;
            const toastType = pendingUninstallToastResult && type === 'success'
                ? getUninstallToastType(pendingUninstallToastResult)
                : type;
            pendingUninstallToastResult = null;
            const id = ++toastIdCounter;
            toasts.value.push({ id, message: toastMessage, type: toastType });
            setTimeout(() => {
                toasts.value = toasts.value.filter(t => t.id !== id);
            }, 3000);
        };

        const summarizeLeakReports = (reports = []) => reports
            .filter(report => report && report.summary)
            .slice(0, 2)
            .map(report => {
                const scope = report.version
                    ? `${report.lingId || activeId.value || 'ling'}@${report.version}`
                    : (report.lingId || activeId.value || 'ling');
                return `${scope}: ${report.summary}`;
            })
            .join('; ');

        const getUninstallToastType = (result) => {
            if (!result || !result.uninstallTriggered) {
                return 'info';
            }
            return result.overallRiskLevel === 'NO_RISK' ? 'success' : 'info';
        };

        const translateOrFallback = (key, fallback, params = {}) => {
            const translated = t(key, params);
            return translated === key ? fallback : translated;
        };

        const buildUninstallToastMessage = (result, fallbackMessage) => {
            if (!result) {
                return fallbackMessage;
            }

            const reportSummary = summarizeLeakReports(result.reports);
            const baseMessage = result.uninstallTriggered === false
                ? translateOrFallback('toast.uninstallTriggeredFalse', fallbackMessage)
                : fallbackMessage;

            if (result.overallRiskLevel === 'NO_RISK') {
                return translateOrFallback(
                    'toast.uninstallPrecheckNoRisk',
                    baseMessage,
                    { message: baseMessage }
                );
            }
            if (result.overallRiskLevel === 'RISK_DETECTED') {
                return reportSummary
                    ? translateOrFallback(
                        'toast.uninstallPrecheckRiskWithSummary',
                        `${baseMessage}: ${reportSummary}`,
                        { message: baseMessage, summary: reportSummary }
                    )
                    : translateOrFallback(
                        'toast.uninstallPrecheckRisk',
                        baseMessage,
                        { message: baseMessage }
                    );
            }
            if (result.overallRiskLevel === 'CHECK_FAILED') {
                return reportSummary
                    ? translateOrFallback(
                        'toast.uninstallPrecheckFailedWithSummary',
                        `${baseMessage}: ${reportSummary}`,
                        { message: baseMessage, summary: reportSummary }
                    )
                    : translateOrFallback(
                        'toast.uninstallPrecheckFailed',
                        baseMessage,
                        { message: baseMessage }
                    );
            }
            return baseMessage;
        };

        const getUninstallRiskLabel = (level) => {
            const keyMap = {
                NO_RISK: 'uninstallResult.levelNoRisk',
                RISK_DETECTED: 'uninstallResult.levelRiskDetected',
                CHECK_FAILED: 'uninstallResult.levelCheckFailed'
            };
            const fallbackMap = {
                NO_RISK: '无明显风险',
                RISK_DETECTED: '发现风险',
                CHECK_FAILED: '预检未完成'
            };
            const key = keyMap[level] || 'uninstallResult.levelUnknown';
            return translateOrFallback(key, fallbackMap[level] || '未知');
        };

        const getUninstallTriggerLabel = (triggered) => translateOrFallback(
            triggered ? 'uninstallResult.triggeredYes' : 'uninstallResult.triggeredNo',
            triggered ? '已触发' : '未触发'
        );

        const getUninstallRiskClass = (level) => ({
            NO_RISK: 'risk-badge risk-safe',
            RISK_DETECTED: 'risk-badge risk-warn',
            CHECK_FAILED: 'risk-badge risk-unknown'
        }[level] || 'risk-badge risk-unknown');

        const openUninstallResultModal = (result, fallbackMessage) => {
            if (!result) {
                return;
            }
            uninstallResultModal.title = translateOrFallback('uninstallResult.title', '卸载结果详情');
            uninstallResultModal.message = buildUninstallToastMessage(result, fallbackMessage);
            uninstallResultModal.result = result;
            uninstallResultModal.show = true;
        };

        const closeUninstallResultModal = () => {
            uninstallResultModal.show = false;
            uninstallResultModal.title = '';
            uninstallResultModal.message = '';
            uninstallResultModal.result = null;
        };

        // ==================== Token 与认证 ====================
        const getToken = () => localStorage.getItem('lingframe_access_token') || '';
        const withAuthHeaders = (headers = {}) => {
            const token = getToken();
            if (token) {
                headers['X-Access-Token'] = token;
            }
            return headers;
        };

        // 认证状态：true 表示已认证（或无需认证），false 表示需要登录
        const authenticated = ref(true);

        // Vue 模板中的认证提交
        const submitAuth = () => {
            const input = document.getElementById('auth-token-input');
            if (!input) return;
            const token = input.value.trim();
            if (!token) return;
            localStorage.setItem('lingframe_access_token', token);
            location.reload();
        };

        const showLoginPrompt = () => {
            // 已存在登录弹窗则不重复创建
            if (document.getElementById('login-overlay')) return;
            authenticated.value = false;

            const isLight = currentTheme.value === 'light';
            const overlay = document.createElement('div');
            overlay.id = 'login-overlay';
            overlay.style.cssText = `position:fixed;top:0;left:0;width:100%;height:100%;background:${isLight ? 'rgba(15,23,42,0.35)' : 'rgba(0,0,0,0.5)'};display:flex;align-items:center;justify-content:center;z-index:10000;`;
            overlay.innerHTML = `
                <div style="background:${isLight ? '#ffffff' : '#1e1e2e'};padding:32px;border-radius:12px;box-shadow:0 8px 32px rgba(0,0,0,${isLight ? '0.1' : '0.3'});width:360px;text-align:center;border:1px solid ${isLight ? '#cbd5e1' : '#313244'};">
                    <div style="font-size:20px;font-weight:600;color:${isLight ? '#0f172a' : '#cdd6f4'};margin-bottom:8px;">${t('login.title', '访问认证')}</div>
                    <div style="font-size:13px;color:${isLight ? '#64748b' : '#a6adc8'};margin-bottom:20px;">${t('login.desc', '请输入访问令牌以继续')}</div>
                    <input id="login-token-input" type="password" placeholder="${t('login.placeholder', 'Access Token')}"
                        style="width:100%;padding:10px 14px;border-radius:8px;border:1px solid ${isLight ? '#cbd5e1' : '#45475a'};background:${isLight ? '#f1f5f9' : '#313244'};color:${isLight ? '#0f172a' : '#cdd6f4'};font-size:14px;outline:none;box-sizing:border-box;" />
                    <div id="login-error" style="color:#f38ba8;font-size:12px;margin-top:8px;display:none;"></div>
                    <button id="login-submit-btn"
                        style="margin-top:16px;width:100%;padding:10px;border-radius:8px;border:none;background:${isLight ? '#3b82f6' : '#89b4fa'};color:#ffffff;font-size:14px;font-weight:600;cursor:pointer;">
                        ${t('login.submit', '确认')}
                    </button>
                </div>
            `;
            document.body.appendChild(overlay);

            const input = document.getElementById('login-token-input');
            const errorDiv = document.getElementById('login-error');
            const submitBtn = document.getElementById('login-submit-btn');

            const doLogin = () => {
                const token = input.value.trim();
                if (!token) {
                    errorDiv.textContent = t('login.emptyToken', '令牌不能为空');
                    errorDiv.style.display = 'block';
                    return;
                }
                localStorage.setItem('lingframe_access_token', token);
                overlay.remove();
                location.reload();
            };

            submitBtn.addEventListener('click', doLogin);
            input.addEventListener('keydown', (e) => { if (e.key === 'Enter') doLogin(); });
            input.focus();
        };

        // ==================== API 调用 ====================
        const api = {
            async get(path) {
                const res = await fetch(API_BASE + path, { credentials: 'same-origin', headers: withAuthHeaders() });
                if (res.status === 401) { showLoginPrompt(); throw new Error('Unauthorized'); }
                if (res.status === 403) { appState.readonly = true; }
                const data = await res.json();
                if (!data.success) throw new Error(data.message);
                return data.data;
            },
            async post(path, body = {}) {
                const res = await fetch(API_BASE + path, {
                    method: 'POST',
                    headers: withAuthHeaders({ 'Content-Type': 'application/json' }),
                    credentials: 'same-origin',
                    body: JSON.stringify(body)
                });
                if (res.status === 401) { showLoginPrompt(); throw new Error('Unauthorized'); }
                if (res.status === 403) { appState.readonly = true; throw new Error(t('toast.readonlyMode', '当前为只读模式')); }
                const data = await res.json();
                if (!data.success) throw new Error(data.message);
                return data.data;
            },
            async delete(path, body = {}) {
                const res = await fetch(API_BASE + path, {
                    method: 'DELETE',
                    headers: withAuthHeaders({ 'Content-Type': 'application/json' }),
                    credentials: 'same-origin',
                    body: JSON.stringify(body)
                });
                if (res.status === 401) { showLoginPrompt(); throw new Error('Unauthorized'); }
                if (res.status === 403) { appState.readonly = true; throw new Error(t('toast.readonlyMode', '当前为只读模式')); }
                const data = await res.json();
                if (!data.success) throw new Error(data.message);
                return data.data;
            }
        };

        // ==================== 灵元操作 ====================
        const refreshLings = async () => {
            loading.lings = true;
            try {
                lings.value = await api.get('/lings');
            } catch (e) {
                showToast(t('toast.getLingsFailed') + ': ' + e.message, 'error');
            } finally {
                loading.lings = false;
            }
        };

        const selectLing = async (lingId) => {
            // ... 其余逻辑保持一致，通常不需要改动内部文案 ...
            if (isAuto.value) {
                toggleAuto(); // 停止压测
            }
            activeId.value = lingId;
            const ling = lings.value.find(p => p.lingId === lingId);
            if (ling) {
                const canaryInfo = ling.versionDetails?.find(v => v.isCanary);
                canaryPct.value = canaryInfo ? canaryInfo.trafficWeight : 0;
            }
            // 重置统计
            Object.assign(stats, { total: 0, v1: 0, v2: 0, v1Pct: 0, v2Pct: 0, active: 0 });
            lastAudit.value = null;

            // 设置 IPC 目标为其他灵元
            const otherLing = lings.value.find(p => p.lingId !== lingId && p.status === 'ACTIVE');
            if (otherLing) {
                ipcTarget.value = otherLing.lingId;
            }

            // 同步 IPC 开关状态
            syncIpcSwitch();
            syncInvocationForm();
        };

        const doUpdateStatus = async (newStatus) => {
            if (!activeId.value) return;
            loading.status = true;
            try {
                const body = { status: newStatus };
                const updated = await api.post(`/lings/${activeId.value}/status`, body);
                const idx = lings.value.findIndex(p => p.lingId === activeId.value);
                if (idx !== -1 && updated) {
                    lings.value[idx] = updated;
                }
                showToast(t('toast.statusUpdated', { status: newStatus }), 'success');
            } catch (e) {
                showToast(t('toast.statusUpdateFailed') + ': ' + e.message, 'error');
            } finally {
                loading.status = false;
            }
        };

        const updateStatus = (newStatus) => {
            if (!activeLing.value) return;

            const currentStatus = activeLing.value.status;
            if (newStatus === 'ACTIVE' && currentStatus !== 'INACTIVE') {
                showToast(t('toast.cannotActivateFrom') + ': ' + currentStatus, 'error');
                return;
            }
            if (newStatus === 'INACTIVE' && currentStatus !== 'ACTIVE' && currentStatus !== 'DEGRADED') {
                showToast(t('toast.cannotDeactivateFrom') + ': ' + currentStatus, 'error');
                return;
            }
            if (newStatus === 'RECOVERING' && currentStatus !== 'DEGRADED') {
                showToast(t('toast.cannotRecoverFrom') + ': ' + currentStatus, 'error');
                return;
            }

            doUpdateStatus(newStatus);
        };

        const requestUnload = () => {
            if (!activeLing.value) return;
            modal.isDanger = true;
            modal.confirmInput = '';
            modal.expectedConfirmInput = activeId.value;
            modal.title = t('modal.confirmUnload');
            modal.message = t('modal.unloadWarning', { lingId: activeId.value });
            modal.actionText = t('modal.unloadAction');
            modal.versionSelectLabel = t('modal.selectVersion');
            const versions = activeLing.value.versionDetails?.map(v => v.version) || [];
            if (versions.length > 1) {
                modal.showVersionSelect = true;
                modal.versions = versions;
                modal.selectedVersion = ''; // 默认全量卸载

                modal.onConfirm = async () => {
                    modal.loading = true;
                    try {
                        let url = `/lings/uninstall/${activeId.value}`;
                        if (modal.selectedVersion) {
                            url += `/${modal.selectedVersion}`;
                        }

                        const result = await api.delete(url);
                        pendingUninstallToastResult = result;

                        if (modal.selectedVersion && modal.versions.length > 1) {
                            // 仅仅是删除了某个版本，刷新部分信息即可
                            showToast(t('toast.lingVersionUnloaded', { version: modal.selectedVersion }) || `版本 ${modal.selectedVersion} 卸载成功`, 'success');
                            openUninstallResultModal(result, t('toast.lingVersionUnloaded', { version: modal.selectedVersion }) || `版本 ${modal.selectedVersion} 卸载成功`);
                            refreshLings(); // 简单起见，重新拉取最新状态
                        } else {
                            // 全量删除 或 最后一个版本被删除
                            lings.value = lings.value.filter(p => p.lingId !== activeId.value);
                            activeId.value = null;
                            Object.assign(stats, { total: 0, v1: 0, v2: 0, v1Pct: 0, v2Pct: 0 });
                            showToast(t('toast.lingUnloaded'), 'success');
                            openUninstallResultModal(result, t('toast.lingUnloaded'));
                        }
                    } catch (e) {
                        showToast(t('toast.unloadFailed') + ': ' + e.message, 'error');
                    } finally {
                        modal.loading = false;
                        modal.show = false;
                    }
                };
                modal.show = true;
                return;
            }

            modal.showVersionSelect = false;
            modal.versions = [];
            modal.selectedVersion = '';
            modal.onConfirm = async () => {
                modal.loading = true;
                try {
                    const result = await api.delete(`/lings/uninstall/${activeId.value}`);
                    pendingUninstallToastResult = result;
                    lings.value = lings.value.filter(p => p.lingId !== activeId.value);
                    activeId.value = null;
                    Object.assign(stats, { total: 0, v1: 0, v2: 0, v1Pct: 0, v2Pct: 0 });
                    showToast(t('toast.lingUnloaded'), 'success');
                    openUninstallResultModal(result, t('toast.lingUnloaded'));
                } catch (e) {
                    showToast(t('toast.unloadFailed') + ': ' + e.message, 'error');
                } finally {
                    modal.loading = false;
                    modal.show = false;
                }
            };
            modal.show = true;
        };

        const confirmModalAction = () => {
            if (modal.onConfirm) modal.onConfirm();
        };

        // ==================== 上传灵元 ====================
        const openUploadModal = () => {
            uploadModal.show = true;
            uploadModal.file = null;
            uploadModal.progress = 0;
            uploadModal.uploading = false;
        };

        const closeUploadModal = () => {
            if (!uploadModal.uploading) {
                uploadModal.show = false;
            }
        };

        // 时间线相关方法
        const openTimelineModal = async () => {
            timelineModal.show = true;
            await loadTimelineData();
        };

        const closeTimelineModal = () => {
            timelineModal.show = false;
        };

        const loadTimelineData = async () => {
            timelineModal.loading = true;
            try {
                let path = '/lings/timeline';
                if (timelineModal.selectedLingId) {
                    path += `?lingId=${timelineModal.selectedLingId}`;
                }
                timelineModal.events = await api.get(path);
            } catch (e) {
                showToast(t('toast.getTimelineFailed') + ': ' + e.message, 'error');
                timelineModal.events = [];
            } finally {
                timelineModal.loading = false;
            }
        };

        const handleFileSelect = (event) => {
            const file = event.target.files[0];
            if (file) validateAndSetFile(file);
            event.target.value = ''; // Reset
        };

        const handleFileDrop = (event) => {
            uploadModal.isDragging = false;
            const file = event.dataTransfer.files[0];
            if (file) validateAndSetFile(file);
        };

        const validateAndSetFile = (file) => {
            if (!file.name.endsWith('.jar')) {
                showToast(t('upload.errorType'), 'error');
                return;
            }
            uploadModal.file = file;
        };

        const formatSize = (bytes) => {
            if (bytes === 0) return '0 B';
            const k = 1024;
            const sizes = ['B', 'KB', 'MB', 'GB'];
            const i = Math.floor(Math.log(bytes) / Math.log(k));
            return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
        };

        const startUpload = () => {
            if (!uploadModal.file) return;

            uploadModal.uploading = true;
            uploadModal.progress = 0;

            const formData = new FormData();
            formData.append('file', uploadModal.file);

            const xhr = new XMLHttpRequest();
            xhr.open('POST', API_BASE + '/lings/install');

            // 设置认证头
            const token = getToken();
            if (token) {
                xhr.setRequestHeader('X-Access-Token', token);
            }

            // 真实上传进度
            xhr.upload.onprogress = (e) => {
                if (e.lengthComputable) {
                    uploadModal.progress = Math.round((e.loaded / e.total) * 100);
                }
            };

            xhr.onload = () => {
                uploadModal.uploading = false;
                try {
                    const data = JSON.parse(xhr.responseText);
                    if (!data.success) throw new Error(data.message);
                    uploadModal.progress = 100;
                    showToast(t('toast.installSuccess'), 'success');
                    closeUploadModal();
                    refreshLings();
                } catch (e) {
                    uploadModal.progress = 0;
                    showToast(t('toast.installFailed') + ': ' + e.message, 'error');
                }
            };

            xhr.onerror = () => {
                uploadModal.uploading = false;
                uploadModal.progress = 0;
                showToast(t('toast.installFailed') + ': 网络错误', 'error');
            };

            xhr.send(formData);
        };

        const doReloadLing = async (lingId, version = '') => {
            loading.lings = true; // 复用 lings loading
            try {
                const body = version ? { version } : {};
                await api.post(`/lings/${lingId}/reload`, body);
                showToast(t('toast.reloadSuccess'), 'success');
                refreshLings();
            } catch (e) {
                showToast(t('toast.reloadFailed') + ': ' + e.message, 'error');
            } finally {
                loading.lings = false;
            }
        };

        const reloadLing = (lingId) => {
            if (!activeLing.value) return;
            modal.isDanger = false;
            modal.confirmInput = '';
            modal.expectedConfirmInput = '';
            const versions = activeLing.value.versionDetails?.map(v => v.version) || [];
            if (versions.length > 1) {
                modal.title = t('modal.reloadTitle') || '确认热重载';
                modal.message = t('modal.reloadMessage') || '请选择版本';
                modal.actionText = t('modal.actionReload') || '热重载';
                modal.versionSelectLabel = t('modal.reloadMessage') || t('modal.selectVersion');
                modal.showVersionSelect = true;
                modal.versions = versions;
                modal.selectedVersion = activeLing.value.activeVersion || '';
                modal.onConfirm = async () => {
                    modal.loading = true;
                    try {
                        await doReloadLing(lingId, modal.selectedVersion);
                    } finally {
                        modal.loading = false;
                        modal.show = false;
                    }
                };
                modal.show = true;
                return;
            }
            doReloadLing(lingId);
        };

        const applyTheme = () => {
            const htmlEl = document.documentElement;
            if (currentTheme.value === 'light') {
                htmlEl.classList.remove('theme-dark');
                htmlEl.classList.add('theme-light');
            } else {
                htmlEl.classList.remove('theme-light');
                htmlEl.classList.add('theme-dark');
            }
            
            // 为了平滑过渡，如果当前是在 monitor 页面，我们直接更新现有图表实例的颜色配置并 update
            if (activeNav.value === 'monitor') {
                const isLight = currentTheme.value === 'light';
                const tooltipBg = isLight ? 'rgba(255,255,255,0.95)' : 'rgba(15,23,42,0.9)';
                const tooltipBorder = isLight ? '#cbd5e1' : '#334155';
                const tooltipTitle = isLight ? '#64748b' : '#94a3b8';
                const tooltipBody = isLight ? '#0f172a' : '#e2e8f0';
                const ticksColor = isLight ? 'rgba(15,23,42,0.6)' : 'rgba(148,163,184,0.5)';
                const gridColor = isLight ? 'rgba(15,23,42,0.08)' : 'rgba(100,116,139,0.1)';

                Object.values(chartInstances).forEach(instance => {
                    if (instance) {
                        instance.options.plugins.tooltip.backgroundColor = tooltipBg;
                        instance.options.plugins.tooltip.borderColor = tooltipBorder;
                        instance.options.plugins.tooltip.titleColor = tooltipTitle;
                        instance.options.plugins.tooltip.bodyColor = tooltipBody;
                        instance.options.scales.x.ticks.color = ticksColor;
                        instance.options.scales.x.grid.color = gridColor;
                        instance.options.scales.y.ticks.color = ticksColor;
                        instance.options.scales.y.grid.color = gridColor;
                        instance.update();
                    }
                });
            } else {
                // 如果不在 monitor 页面，销毁图表
                destroyCharts();
            }

            nextTick(() => {
                // 星图也需要重绘以适配主题色
                if (starMapAnimFrame) cancelAnimationFrame(starMapAnimFrame);
                drawStarMap();
            });
        };

        const toggleTheme = () => {
            currentTheme.value = currentTheme.value === 'dark' ? 'light' : 'dark';
            localStorage.setItem('lingframe_theme', currentTheme.value);
            applyTheme();
        };

        const fetchPackages = async () => {
            try {
                packages.value = await api.get('/lings/packages');
            } catch (e) {
                showToast(t('toast.getLingsFailed') + ': ' + e.message, 'error');
            }
        };

        const deployPackage = async (lingId, version) => {
            try {
                await api.post('/lings/packages/deploy', { lingId, version });
                showToast(t('toastExtension.deploySuccess') || '灵元部署成功', 'success');
                refreshLings();
                fetchPackages();
            } catch (e) {
                showToast((t('toastExtension.deployFailed') || '灵元部署失败') + ': ' + e.message, 'error');
            }
        };

        const deletePackageFile = (lingId, version) => {
            modal.isDanger = false;
            modal.confirmInput = '';
            modal.expectedConfirmInput = '';
            modal.title = t('lingCenter.uninstallDeleteFile') || '彻底删除物理包';
            modal.message = `确认要彻底从磁盘中删除灵元 ${lingId} (版本 ${version}) 的 JAR 文件吗？此操作无法撤销。`;
            modal.actionText = t('modal.confirm') || '确认';
            modal.showVersionSelect = false;
            modal.showDeleteFileOption = false;
            modal.onConfirm = async () => {
                modal.loading = true;
                try {
                    await api.delete(`/lings/uninstall/${lingId}/${version}?deleteFile=true`);
                    showToast(t('toastExtension.uninstallDeleteFileSuccess') || '彻底卸载成功，物理包已从磁盘删除', 'success');
                    fetchPackages();
                } catch (e) {
                    showToast(t('toast.unloadFailed') + ': ' + e.message, 'error');
                } finally {
                    modal.loading = false;
                    modal.show = false;
                }
            };
            modal.show = true;
        };

        const updateStatusForLing = async (lingId, newStatus) => {
            const ling = lings.value.find(p => p.lingId === lingId);
            if (!ling) return;

            const currentStatus = ling.status;
            if (newStatus === 'ACTIVE' && currentStatus !== 'INACTIVE') {
                showToast(t('toast.cannotActivateFrom') + ': ' + currentStatus, 'error');
                return;
            }
            if (newStatus === 'INACTIVE' && currentStatus !== 'ACTIVE' && currentStatus !== 'DEGRADED') {
                showToast(t('toast.cannotDeactivateFrom') + ': ' + currentStatus, 'error');
                return;
            }
            if (newStatus === 'RECOVERING' && currentStatus !== 'DEGRADED') {
                showToast(t('toast.cannotRecoverFrom') + ': ' + currentStatus, 'error');
                return;
            }

            loading.status = true;
            try {
                const body = { status: newStatus };
                const updated = await api.post(`/lings/${lingId}/status`, body);
                const idx = lings.value.findIndex(p => p.lingId === lingId);
                if (idx !== -1 && updated) {
                    lings.value[idx] = updated;
                }
                showToast(t('toast.statusUpdated', { status: newStatus }), 'success');
            } catch (e) {
                showToast(t('toast.statusUpdateFailed') + ': ' + e.message, 'error');
            } finally {
                loading.status = false;
            }
        };

        const getLingCanaryWeight = (ling) => {
            if (!ling || !ling.versionDetails) return 0;
            const canary = ling.versionDetails.find(v => v.isCanary);
            return canary ? canary.trafficWeight : 0;
        };

        const updateCanaryWeight = async (lingId, weight) => {
            const ling = lings.value.find(p => p.lingId === lingId);
            if (!ling || !ling.versionDetails) return;
            const pct = parseInt(weight, 10);

            const canaryVer = ling.versionDetails.find(v => v.isCanary)?.version
                || ling.versionDetails.find(v => !v.isDefault)?.version;
            if (!canaryVer) return;

            ling.versionDetails.forEach(v => {
                if (v.version === canaryVer) {
                    v.trafficWeight = pct;
                } else {
                    v.trafficWeight = 100 - pct;
                }
            });
            if (activeId.value === lingId) {
                canaryPct.value = pct;
            }

            try {
                await api.post(`/lings/${lingId}/canary`, {
                    percent: pct,
                    canaryVersion: canaryVer
                });
                showToast(t('toast.canarySet', { percent: pct }), 'success');
            } catch (e) {
                showToast(t('toast.canaryFailed') + ': ' + e.message, 'error');
            }
        };

        const requestUnloadWithName = (lingId, deleteFileOption = false) => {
            const ling = lings.value.find(p => p.lingId === lingId);
            modal.isDanger = true;
            modal.confirmInput = '';
            modal.expectedConfirmInput = lingId;
            modal.title = t('modal.confirmUnload');
            modal.message = t('modal.unloadWarning', { lingId });
            modal.actionText = t('modal.unloadAction');
            modal.versionSelectLabel = t('modal.selectVersion');
            modal.showDeleteFileOption = deleteFileOption;
            modal.deleteFile = false;

            const versions = ling ? (ling.versionDetails?.map(v => v.version) || []) : [];
            if (versions.length > 1) {
                modal.showVersionSelect = true;
                modal.versions = versions;
                modal.selectedVersion = ''; // 默认全量卸载

                modal.onConfirm = async () => {
                    modal.loading = true;
                    try {
                        let url = `/lings/uninstall/${lingId}`;
                        if (modal.selectedVersion) {
                            url += `/${modal.selectedVersion}`;
                        }
                        url += `?deleteFile=${modal.deleteFile}`;

                        const result = await api.delete(url);
                        pendingUninstallToastResult = result;

                        if (modal.selectedVersion && modal.versions.length > 1) {
                            showToast(t('toast.lingVersionUnloaded', { version: modal.selectedVersion }) || `版本 ${modal.selectedVersion} 卸载成功`, 'success');
                            openUninstallResultModal(result, t('toast.lingVersionUnloaded', { version: modal.selectedVersion }) || `版本 ${modal.selectedVersion} 卸载成功`);
                            refreshLings();
                            fetchPackages();
                        } else {
                            lings.value = lings.value.filter(p => p.lingId !== lingId);
                            if (activeId.value === lingId) {
                                activeId.value = null;
                                Object.assign(stats, { total: 0, v1: 0, v2: 0, v1Pct: 0, v2Pct: 0 }); // Reset stats
                            }
                            showToast(t('toast.lingUnloaded'), 'success');
                            openUninstallResultModal(result, t('toast.lingUnloaded'));
                            refreshLings();
                            fetchPackages();
                        }
                    } catch (e) {
                        showToast(t('toast.unloadFailed') + ': ' + e.message, 'error');
                    } finally {
                        modal.loading = false;
                        modal.show = false;
                    }
                };
                modal.show = true;
                return;
            }

            modal.showVersionSelect = false;
            modal.versions = [];
            modal.selectedVersion = '';
            modal.onConfirm = async () => {
                modal.loading = true;
                try {
                    const result = await api.delete(`/lings/uninstall/${lingId}?deleteFile=${modal.deleteFile}`);
                    pendingUninstallToastResult = result;
                    lings.value = lings.value.filter(p => p.lingId !== lingId);
                    if (activeId.value === lingId) {
                        activeId.value = null;
                        Object.assign(stats, { total: 0, v1: 0, v2: 0, v1Pct: 0, v2Pct: 0 }); // Reset stats
                    }
                    showToast(t('toast.lingUnloaded'), 'success');
                    openUninstallResultModal(result, t('toast.lingUnloaded'));
                    refreshLings();
                    fetchPackages();
                } catch (e) {
                    showToast(t('toast.unloadFailed') + ': ' + e.message, 'error');
                } finally {
                    modal.loading = false;
                    modal.show = false;
                }
            };
            modal.show = true;
        };

        const requestUnloadSpecific = (lingId, version, deleteFileOption = false) => {
            modal.isDanger = true;
            modal.confirmInput = '';
            modal.expectedConfirmInput = lingId;
            modal.title = t('modal.confirmUnload');
            modal.message = t('modal.unloadWarningSpecific', { lingId, version }) || `确认卸载服务 ${lingId} 的版本 ${version} 吗？`;
            modal.actionText = t('modal.unloadAction');
            modal.showVersionSelect = false; // 已指定版本，无需选择
            modal.showDeleteFileOption = deleteFileOption;
            modal.deleteFile = false;

            modal.onConfirm = async () => {
                modal.loading = true;
                try {
                    const result = await api.delete(`/lings/uninstall/${lingId}/${version}?deleteFile=${modal.deleteFile}`);
                    pendingUninstallToastResult = result;
                    showToast(t('toast.lingVersionUnloaded', { version }) || `版本 ${version} 卸载成功`, 'success');
                    openUninstallResultModal(result, t('toast.lingVersionUnloaded', { version }) || `版本 ${version} 卸载成功`);
                    refreshLings();
                    fetchPackages();
                } catch (e) {
                    showToast(t('toast.unloadFailed') + ': ' + e.message, 'error');
                } finally {
                    modal.loading = false;
                    modal.show = false;
                }
            };
            modal.show = true;
        };

        const updateCanaryConfig = async () => {
            if (!activeId.value || !canCanary.value) return;

            loading.canary = true;
            try {
                await api.post(`/lings/${activeId.value}/canary`, {
                    percent: canaryPct.value,
                    canaryVersion: activeLing.value?.versionDetails?.find(v => v.isCanary)?.version
                        || activeLing.value?.versionDetails?.find(v => !v.isDefault)?.version
                });
                showToast(t('toast.canarySet', { percent: canaryPct.value }), 'success');
            } catch (e) {
                showToast(t('toast.canaryFailed') + ': ' + e.message, 'error');
            } finally {
                loading.canary = false;
            }
        };

        const updateCanaryConfigLocally = () => {
            // 实现丝滑的即时同步: 深度更新全量响应式数据
            if (activeLing.value && activeLing.value.versionDetails) {
                // 1. 同步更新当前选中对象的内部比例
                activeLing.value.versionDetails.forEach(v => {
                    if (v.isCanary) {
                        v.trafficWeight = canaryPct.value;
                    } else if (v.isDefault) {
                        v.trafficWeight = 100 - canaryPct.value;
                    }
                });

                // 2. 核心补救：强制更新 lings 列表中的引用，触发侧边栏响应式重绘
                const idx = lings.value.findIndex(p => p.lingId === activeId.value);
                if (idx !== -1) {
                    // 使用展开运算符保持响应式，或者直接替换对象
                    // 这里我们通过重新赋值来确保 Vue 检测到变化
                    lings.value[idx] = { ...lings.value[idx] };
                }

                // 3. 同步更新中间统计卡片
                stats.v2Pct = canaryPct.value;
                stats.v1Pct = 100 - canaryPct.value;
            }
        };

        const resetCanary = async () => {
            if (!activeId.value) return;
            canaryPct.value = 0;
            updateCanaryConfigLocally();
            await updateCanaryConfig();
        };

        const normalizeNullableInt = (value) => {
            if (value === '' || value === null || value === undefined) {
                return null;
            }
            const parsed = Number(value);
            return Number.isFinite(parsed) ? Math.trunc(parsed) : null;
        };

        const syncInvocationForm = () => {
            const current = activeLing.value?.invocationGovernance || {};
            invocationForm.timeoutMs = current.timeoutMs ?? '';
            invocationForm.rateLimitPerSecond = current.rateLimitPerSecond ?? '';
            invocationForm.maxConcurrentThreads = current.maxConcurrentThreads ?? '';
            invocationForm.retryCount = current.retryCount ?? '';
            invocationForm.fallbackValue = current.fallbackValue ?? '';
            invocationForm.cpuBudgetMsPerMinute = current.cpuBudgetMsPerMinute ?? '';
            invocationForm.memoryBudgetMb = current.memoryBudgetMb ?? '';
        };

        const saveInvocationGovernance = async () => {
            if (!activeId.value) return;

            loading.invocation = true;
            try {
                const updated = await api.post(`/governance/${activeId.value}/invocation`, {
                    timeoutMs: normalizeNullableInt(invocationForm.timeoutMs),
                    rateLimitPerSecond: normalizeNullableInt(invocationForm.rateLimitPerSecond),
                    maxConcurrentThreads: normalizeNullableInt(invocationForm.maxConcurrentThreads),
                    retryCount: normalizeNullableInt(invocationForm.retryCount),
                    fallbackValue: invocationForm.fallbackValue || null,
                    cpuBudgetMsPerMinute: normalizeNullableInt(invocationForm.cpuBudgetMsPerMinute),
                    memoryBudgetMb: normalizeNullableInt(invocationForm.memoryBudgetMb)
                });

                const idx = lings.value.findIndex(p => p.lingId === activeId.value);
                if (idx !== -1) {
                    lings.value[idx].invocationGovernance = updated;
                }
                syncInvocationForm();
                showToast(t('toast.invocationUpdated'), 'success');
            } catch (e) {
                showToast(t('toast.invocationUpdateFailed') + ': ' + e.message, 'error');
            } finally {
                loading.invocation = false;
            }
        };

        // ==================== 权限操作 ====================
        const togglePerm = async (perm) => {
            if (!activeLing.value) return;

            const permMap = {
                'DB_READ': 'dbRead',
                'DB_WRITE': 'dbWrite',
                'CACHE_READ': 'cacheRead',
                'CACHE_WRITE': 'cacheWrite'
            };
            const mappedPerm = permMap[perm] || perm;

            const currentPerms = activeLing.value.permissions || {};
            const currentValue = currentPerms[mappedPerm] !== false;
            const newValue = !currentValue;

            // 构建新的权限状态
            const newPerms = {
                dbRead: currentPerms.dbRead !== false,
                dbWrite: currentPerms.dbWrite !== false,
                cacheRead: currentPerms.cacheRead !== false,
                cacheWrite: currentPerms.cacheWrite !== false,
                ipcServices: currentPerms.ipcServices || []
            };
            newPerms[mappedPerm] = newValue;

            // 权限级联逻辑
            if (mappedPerm === 'dbWrite' && newValue) {
                newPerms.dbRead = true;
            }
            if (mappedPerm === 'cacheWrite' && newValue) {
                newPerms.cacheRead = true;
            }

            if (mappedPerm === 'dbRead' && !newValue) {
                newPerms.dbWrite = false;
            }
            if (mappedPerm === 'cacheRead' && !newValue) {
                newPerms.cacheWrite = false;
            }

            loading.permissions = true;
            try {
                await api.post(`/governance/${activeId.value}/permissions`, newPerms);
                const idx = lings.value.findIndex(p => p.lingId === activeId.value);
                if (idx !== -1) {
                    lings.value[idx].permissions = newPerms;
                }

                // 乐观更新以保证界面即时渲染
                if (lingGovernanceMetrics[activeId.value]) {
                    if (!lingGovernanceMetrics[activeId.value].summary) {
                        lingGovernanceMetrics[activeId.value].summary = {};
                    }
                    const summary = lingGovernanceMetrics[activeId.value].summary;
                    summary.dbReadEnabled = newPerms.dbRead;
                    summary.dbWriteEnabled = newPerms.dbWrite;
                    summary.cacheReadEnabled = newPerms.cacheRead;
                    summary.cacheWriteEnabled = newPerms.cacheWrite;
                }

                // 改进提示信息，说明级联效果
                let message = newValue ? t('toast.permEnabled', { perm: mappedPerm }) : t('toast.permDisabled', { perm: mappedPerm });
                if (mappedPerm === 'dbWrite' && newValue && !currentPerms.dbRead) {
                    message += t('toast.alsoEnabled', { perm: 'dbRead' });
                } else if (mappedPerm === 'cacheWrite' && newValue && !currentPerms.cacheRead) {
                    message += t('toast.alsoEnabled', { perm: 'cacheRead' });
                } else if (mappedPerm === 'dbRead' && !newValue && currentPerms.dbWrite) {
                    message += t('toast.alsoDisabled', { perm: 'dbWrite' });
                } else if (mappedPerm === 'cacheRead' && !newValue && currentPerms.cacheWrite) {
                    message += t('toast.alsoDisabled', { perm: 'cacheWrite' });
                }

                showToast(message, 'success');
                await fetchLingGovernanceMetrics();
            } catch (e) {
                showToast(t('toast.permUpdateFailed') + ': ' + e.message, 'error');
            } finally {
                loading.permissions = false;
            }
        };

        const syncIpcSwitch = () => {
            // ...
            if (!activeLing.value || !ipcTarget.value) {
                ipcEnabled.value = false;
                return;
            }
            const currentPerms = activeLing.value.permissions || {};
            const services = currentPerms.ipcServices || [];
            ipcEnabled.value = services.includes(ipcTarget.value);
        };

        const toggleIpc = async () => {
            // ... 开关切换逻辑 ...
            if (!activeLing.value || !ipcTarget.value) return;

            // 切换状态
            const newValue = !ipcEnabled.value;
            const currentPerms = activeLing.value.permissions || {};
            const currentServices = currentPerms.ipcServices || [];

            // 更新服务列表
            let newServices;
            if (newValue) {
                newServices = [...new Set([...currentServices, ipcTarget.value])];
            } else {
                newServices = currentServices.filter(s => s !== ipcTarget.value);
            }

            // 构建完整权限对象
            const newPerms = {
                // ... 复制权限配置 ...
                dbRead: currentPerms.dbRead !== false,
                dbWrite: currentPerms.dbWrite !== false,
                cacheRead: currentPerms.cacheRead !== false,
                cacheWrite: currentPerms.cacheWrite !== false,
                ipcServices: newServices
            };

            loading.permissions = true;
            try {
                await api.post(`/governance/${activeId.value}/permissions`, newPerms);

                // 更新本地状态
                const idx = lings.value.findIndex(p => p.lingId === activeId.value);
                if (idx !== -1) {
                    lings.value[idx].permissions = newPerms;
                }
                ipcEnabled.value = newValue; // 更新开关视觉

                showToast(newValue ? t('toast.ipcEnabled') : t('toast.ipcDisabled'), 'success');
            } catch (e) {
                showToast(t('toast.ipcUpdateFailed') + ': ' + e.message, 'error');
            } finally {
                loading.permissions = false;
            }
        };

        // ==================== 功能演练 ====================
        const simulate = async (resourceType) => {
            if (!canOperate.value) {
                showToast(t('toast.lingNotActive'), 'error');
                return;
            }

            loading.simulate = true;
            try {
                const result = await api.post(`/simulate/lings/${activeId.value}/resource`, {
                    resourceType
                });
                lastAudit.value = result;

                if (result.allowed) {
                    showToast(t('toast.accessSuccess', { type: resourceType }), 'success');
                } else {
                    showToast(result.message, 'error');
                }
            } catch (e) {
                showToast(t('toast.simulateFailed') + ': ' + e.message, 'error');
            } finally {
                loading.simulate = false;
            }
        };

        const simulateIPC = async () => {
            if (!canOperate.value) {
                showToast(t('toast.sourceLingNotActive'), 'error');
                return;
            }

            loading.simulate = true;
            try {
                const result = await api.post(`/simulate/lings/${activeId.value}/ipc`, {
                    targetLingId: ipcTarget.value,
                    ipcEnabled: ipcEnabled.value
                });
                lastAudit.value = result;

                if (result.allowed) {
                    showToast(t('toast.ipcSuccess'), 'success');
                } else {
                    showToast(result.message, 'error');
                }
            } catch (e) {
                showToast(t('toast.ipcSimulateFailed') + ': ' + e.message, 'error');
            } finally {
                loading.simulate = false;
            }
        };

        // ==================== 压测模式 ====================
        const toggleAuto = () => {
            if (!canOperate.value) {
                showToast(t('toast.lingNotActive'), 'error');
                return;
            }

            isAuto.value = !isAuto.value;

            if (isAuto.value) {
                // 开始压测
                stressTimer = setInterval(async () => {
                    try {
                        const result = await api.post(`/simulate/lings/${activeId.value}/stress`);
                        // 更新统计
                        stats.total += result.totalRequests;
                        stats.v1 += result.v1Requests;
                        stats.v2 += result.v2Requests;
                        stats.active = result.activeRequests || 0;
                        stats.v1Pct = stats.total > 0 ? ((stats.v1 / stats.total) * 100).toFixed(1) : 0;
                        stats.v2Pct = stats.total > 0 ? ((stats.v2 / stats.total) * 100).toFixed(1) : 0;
                    } catch (e) {
                        console.error('Stress test error', e);
                        // 🔥 关键修复：发生严重错误（如灵元已卸载）时，自动停止压测
                        if (isAuto.value) {
                            isAuto.value = false;
                            if (stressTimer) {
                                clearInterval(stressTimer);
                                stressTimer = null;
                            }
                            showToast(t('toast.stressStopped') || '压测已自动停止: ' + e.message, 'warn');
                        }
                    }
                }, 1000);
            } else {
                // 停止压测
                if (stressTimer) {
                    clearInterval(stressTimer);
                    stressTimer = null;
                }
            }
        };

        const resetStats = () => {
            Object.assign(stats, { total: 0, v1: 0, v2: 0, v1Pct: 0, v2Pct: 0, active: 0 });
            lastAudit.value = null;
        };

        // ==================== 服务演练场 ====================
        const playgroundServices = ref([]);
        const playgroundLoading = ref(false);
        const playgroundInvoking = ref({});
        const playgroundArgs = reactive({});
        const playgroundResult = ref(null);

        const expandedServices = ref({});
        const toggleServiceExpand = (fqsid) => {
            expandedServices.value[fqsid] = !expandedServices.value[fqsid];
        };
        const isServiceExpanded = (fqsid) => {
            return !!expandedServices.value[fqsid];
        };

        const fetchPlaygroundServices = async () => {
            if (!activeId.value) {
                playgroundServices.value = [];
                return;
            }
            playgroundLoading.value = true;
            try {
                const data = await api.get(`/playground/lings/${activeId.value}/services`);
                playgroundServices.value = data || [];
                playgroundResult.value = null;
                expandedServices.value = {};
            } catch (e) {
                playgroundServices.value = [];
            } finally {
                playgroundLoading.value = false;
            }
        };

        const invokeService = async (fqsid, method) => {
            const key = `${fqsid}::${method.signature}`;
            playgroundInvoking[key] = true;
            try {
                // 按参数索引收集各输入框的值
                const paramTypes = method.parameterTypes || [];
                const args = paramTypes.map((_, idx) => playgroundArgs[key + '::' + idx] ?? null);
                const targetFqsid = method.alternateFqsid || fqsid;
                const result = await api.post(`/playground/lings/${activeId.value}/invoke`, {
                    fqsid: targetFqsid,
                    methodName: method.name,
                    parameterTypes: paramTypes,
                    args: args
                });
                playgroundResult.value = result;
            } catch (e) {
                playgroundResult.value = { success: false, error: e.message, durationMs: 0, traces: [] };
            } finally {
                playgroundInvoking[key] = false;
            }
        };

        const getInvokeKey = (fqsid, signature) => `${fqsid}::${signature}`;

        const isComplexParameterType = (typeName) => {
            if (!typeName) return false;
            const baseTypes = [
                'int', 'long', 'double', 'float', 'boolean', 'char', 'byte', 'short',
                'java.lang.String', 'java.lang.Long', 'java.lang.Integer', 'java.lang.Double',
                'java.lang.Float', 'java.lang.Boolean', 'java.lang.Character', 'java.lang.Byte',
                'java.lang.Short'
            ];
            return !baseTypes.includes(typeName);
        };

        const prefillJsonTemplate = (fqsid, signature, idx) => {
            const key = getInvokeKey(fqsid, signature) + '::' + idx;
            playgroundArgs[key] = '{\n  "id": 1,\n  "name": "test"\n}';
        };

        // 选中灵元时自动加载服务列表
        watch(activeId, (newId) => {
            playgroundServices.value = [];
            playgroundResult.value = null;
            // 切换灵元后主区域滚动到顶部
            const mainArea = document.querySelector('.main-area');
            if (mainArea) mainArea.scrollTop = 0;
            if (newId) {
                fetchPlaygroundServices();
            }
        });

        // ==================== SSE 日志流 ====================
        let sseRetryDelay = 1000; // 初始重连延迟 1s
        const SSE_RETRY_MAX = 30000; // 最大延迟 30s
        let sseRetryTimer = null;

        const connectSSE = async () => {
            if (sseRetryTimer) { clearTimeout(sseRetryTimer); sseRetryTimer = null; }
            if (eventSource) {
                eventSource.close();
            }

            sseStatus.value = 'connecting';

            // SSE 认证：先获取 ticket，再用 ticket 连接（EventSource 不支持自定义 Header）
            try {
                const ticketData = await api.get('/stream-ticket');
                const ticketParam = ticketData.ticket ? '?ticket=' + encodeURIComponent(ticketData.ticket) : '';
                eventSource = new EventSource(API_BASE + '/stream' + ticketParam);
            } catch (e) {
                // ticket 获取失败，尝试无 ticket 连接（兼容未启用 token 的场景）
                eventSource = new EventSource(API_BASE + '/stream');
            }

            eventSource.onopen = () => {
                sseStatus.value = 'connected';
                sseRetryDelay = 1000; // 连接成功，重置延迟
            };

            eventSource.onmessage = () => {
                // 通用消息（忽略）
            };

            eventSource.addEventListener('log-event', (e) => {
                try {
                    const data = JSON.parse(e.data);
                    addLog(data);
                } catch (err) {
                    // 忽略解析失败的日志
                }
            });

            eventSource.addEventListener('ping', () => {
                // 心跳
            });

            eventSource.addEventListener('auth-error', () => {
                // SSE 认证失败，关闭连接并引导重新登录
                eventSource.close();
                eventSource = null;
                sseStatus.value = 'disconnected';
                showLoginPrompt();
            });

            eventSource.onerror = () => {
                sseStatus.value = 'disconnected';
                if (sseRetryTimer) clearTimeout(sseRetryTimer);
                sseRetryTimer = setTimeout(connectSSE, sseRetryDelay);
                sseRetryDelay = Math.min(sseRetryDelay * 2, SSE_RETRY_MAX);
            };
        };

        const addLog = (data) => {
            const log = {
                id: ++logIdCounter,
                traceId: data.traceId,
                lingId: data.lingId,
                version: data.version,
                content: data.content,
                type: data.type,
                tag: data.tag,
                depth: data.depth || 0,
                timestamp: data.timestamp
            };

            // 暂停时只缓存，不操作 logs 数组
            if (logPaused.value) {
                if (logPausedBuffer.length < 500) {
                    logPausedBuffer.push(log);
                }
                return;
            }

            logs.value.unshift(log);
            if (logs.value.length > 1000) {
                logs.value.pop();
            }

            if (!consoleExpanded.value) {
                hasNewTraceAlert.value = true;
            }

            // 自动滚动
            if (!isUserScrolling.value && logContainer.value) {
                nextTick(() => {
                    logContainer.value.scrollTop = 0;
                });
            }

            if (autoScrollLogs.value && globalLogContainer.value) {
                nextTick(() => {
                    globalLogContainer.value.scrollTop = globalLogContainer.value.scrollHeight;
                });
            }
        };

        const clearLogs = () => {
            if (logViewMode.value === 'current' && activeId.value) {
                logs.value = logs.value.filter(l => l.lingId !== activeId.value);
            } else {
                logs.value = [];
            }
        };

        // ==================== 辅助函数 ====================
        const handleLogScroll = () => {
            if (logContainer.value) {
                isUserScrolling.value = logContainer.value.scrollTop > 50;
            }
        };

        // 聚合日志
        const aggregateLogs = (logs) => {
            const aggregated = {};

            logs.forEach(log => {
                // 按类型、内容和版本聚合
                const key = `${log.type || 'UNKNOWN'}-${log.content}-${log.version || 'UNKNOWN'}`;
                if (!aggregated[key]) {
                    aggregated[key] = {
                        id: ++logIdCounter,
                        type: log.type,
                        content: log.content,
                        lingId: log.lingId,
                        version: log.version,
                        count: 0,
                        firstTimestamp: log.timestamp,
                        lastTimestamp: log.timestamp,
                        logs: []
                    };
                }

                const entry = aggregated[key];
                entry.count++;
                entry.firstTimestamp = Math.min(entry.firstTimestamp, log.timestamp);
                entry.lastTimestamp = Math.max(entry.lastTimestamp, log.timestamp);
                entry.logs.push(log);
            });

            // 转换为数组并按最后时间排序
            return Object.values(aggregated).sort((a, b) => b.lastTimestamp - a.firstTimestamp);
        };

        // 筛选日志
        const filterLogs = () => {
            // 筛选逻辑已在displayLogs计算属性中实现
        };

        // 重置日志筛选
        const resetLogFilters = () => {
            logFilters.version = '';
            logFilters.eventType = '';
            logFilters.keyword = '';
        };

        const scrollToTop = () => {
            if (logContainer.value) {
                logContainer.value.scrollTo({ top: 0, behavior: 'smooth' });
                isUserScrolling.value = false;
            }
        };

        const updateTime = () => {
            currentTime.value = new Date().toLocaleTimeString(locale.value === 'zh-CN' ? 'zh-CN' : 'en-US', { hour12: false });
        };

        const formatDrift = (val) => {
            const v = val || 0;
            return (v >= 0 ? '+' : '') + v.toFixed(1) + '%';
        };

        const formatMetricNumber = (value, digits = 1) => {
            const num = Number(value || 0);
            return Number.isFinite(num) ? num.toFixed(digits) : (0).toFixed(digits);
        };

        const getLingHealthStatusClass = (status) => ({
            HEALTHY: 'risk-badge risk-safe',
            WARNING: 'risk-badge risk-warn',
            UNHEALTHY: 'risk-badge risk-danger',
            UNKNOWN: 'risk-badge risk-unknown'
        }[status] || 'risk-badge risk-unknown');

        const getLingHealthRoleLabel = (versionInfo) => {
            if (!versionInfo) {
                return t('healthCard.unknown');
            }
            if (versionInfo.isDefault) {
                return t('traffic.stable');
            }
            if (versionInfo.isCanary) {
                return t('traffic.canary');
            }
            return t('healthCard.version');
        };

        const hasGovernanceSignals = (metrics) => {
            if (!metrics) {
                return false;
            }
            return Number(metrics.rateLimitedRequests || 0) > 0
                || Number(metrics.timeoutRequests || 0) > 0
                || Number(metrics.circuitOpenRejections || 0) > 0
                || Number(metrics.circuitOpenedCount || 0) > 0
                || Number(metrics.bulkheadRejectedRequests || 0) > 0
                || Number(metrics.threadBudgetExceededCount || 0) > 0
                || Number(metrics.cpuBudgetExceededCount || 0) > 0
                || Number(metrics.memoryBudgetExceededCount || 0) > 0;
        };

        const formatBudgetPercent = (used, budget) => {
            const usedNum = Number(used || 0);
            const budgetNum = Number(budget || 0);
            if (!Number.isFinite(usedNum) || !Number.isFinite(budgetNum) || budgetNum <= 0) {
                return '--';
            }
            return `${((usedNum * 100) / budgetNum).toFixed(1)}%`;
        };

        const formatBudgetValue = (value, suffix = '') => {
            if (value === null || value === undefined || value === '') {
                return '--';
            }
            return `${value}${suffix}`;
        };

        const formatTime = (ts) => {
            if (!ts) return '--:--:--';
            const d = new Date(ts);
            return d.toLocaleTimeString(locale.value === 'zh-CN' ? 'zh-CN' : 'en-US', { hour12: false });
        };

        const getStatusClass = (status) => ({
            'ACTIVE': 'status-active',
            'INACTIVE': 'status-loaded',
            'READY': 'status-loaded',
            'READY_FOR_USE': 'status-loaded',
            'IN_USE': 'status-active',
            'DEGRADED': 'status-error',
            'RECOVERING': 'status-loading',
            'REMOVED': 'status-unloaded',
            'RETIRED': 'status-unloaded',
            'STARTING': 'status-loading',
            'STOPPING': 'status-loading'
        }[status] || 'status-unloaded');

        const getLingShortName = (pid) => {
            if (!pid) return '---';
            const parts = pid.split('-');
            return parts[0]?.toUpperCase() || pid.toUpperCase();
        };

        const getLingTagClass = (pid) => {
            const colors = [
                'bg-blue-500/20 text-blue-400',
                'bg-amber-500/20 text-amber-400',
                'bg-green-500/20 text-green-400',
                'bg-purple-500/20 text-purple-400',
                'bg-pink-500/20 text-pink-400'
            ];
            const idx = lings.value.findIndex(p => p.lingId === pid);
            return colors[idx % colors.length] || colors[0];
        };

        // 灵元健康指示灯样式
        const getLingHealthDotClass = (lingId) => {
            const ling = lings.value.find(p => p.lingId === lingId);
            const health = lingHealthMetrics[lingId]?.summary;
            const isActive = ling && (ling.status === 'ACTIVE' || ling.status === 'DEGRADED');

            if (health) {
                const status = health.healthStatus;
                if (status === 'HEALTHY') return isActive ? 'healthy pulse' : 'healthy';
                if (status === 'WARNING') return 'degraded';
                if (status === 'UNHEALTHY') return 'unhealthy';
            }
            // 无健康数据时，根据灵元状态推断
            if (isActive) return 'healthy pulse';
            if (ling?.status === 'INACTIVE') return 'unknown';
            return 'unknown';
        };

        // 灵元运行时长（优先使用后端 installedAt，兜底用前端首次发现时间）
        const getLingUptime = (lingId) => {
            const ling = lings.value.find(p => p.lingId === lingId);
            const startTime = ling?.installedAt || lingFirstSeen[lingId];
            if (!startTime) return '';
            const diff = Date.now() - startTime;
            const minutes = Math.floor(diff / 60000);
            if (minutes < 1) return '<1m';
            if (minutes < 60) return minutes + 'm';
            const hours = Math.floor(minutes / 60);
            const remainMin = minutes % 60;
            if (hours < 24) return hours + 'h ' + remainMin + 'm';
            const days = Math.floor(hours / 24);
            const remainHours = hours % 24;
            return days + 'd ' + remainHours + 'h';
        };

        // 灵元服务数量
        const getLingServiceCount = (lingId) => {
            return lingServiceCounts[lingId] || 0;
        };

        const getLogColor = (log) => {
            if (log.tag === 'FAIL' || log.tag === 'ERROR') return 'text-red-400';
            if (log.tag === 'OK' || log.tag === 'COMPLETE') return 'text-green-400';
            if (log.type === 'AUDIT') return 'text-indigo-400';
            if (log.tag === 'IN') return 'text-blue-400';
            if (log.tag === 'OUT') return 'text-amber-400';
            if (log.tag === 'CANARY') return 'text-amber-400';
            if (log.tag === 'STABLE') return 'text-blue-400';
            if (log.tag === 'START' || log.tag === 'SUMMARY') return 'text-purple-400';
            return 'text-slate-400';
        };

        // 时间线事件样式和图标
        const getTimelineEventClass = (type) => {
            switch (type) {
                case 'READY':
                    return 'bg-blue-500/20 text-blue-400 border-2 border-blue-500';
                case 'ACTIVE':
                    return 'bg-green-500/20 text-green-400 border-2 border-green-500';
                case 'RECOVERING':
                    return 'bg-cyan-500/20 text-cyan-400 border-2 border-cyan-500';
                case 'STOPPING':
                    return 'bg-amber-500/20 text-amber-400 border-2 border-amber-500';
                case 'DEAD':
                    return 'bg-red-500/20 text-red-400 border-2 border-red-500';
                case 'RELOAD':
                    return 'bg-purple-500/20 text-purple-400 border-2 border-purple-500';
                case 'UNLOAD':
                    return 'bg-pink-500/20 text-pink-400 border-2 border-pink-500';
                case 'GC':
                    return 'bg-cyan-500/20 text-cyan-400 border-2 border-cyan-500';
                default:
                    return 'bg-slate-500/20 text-slate-400 border-2 border-slate-500';
            }
        };

        const getTimelineEventIcon = (type) => {
            switch (type) {
                case 'READY':
                    return 'fa-solid fa-check-circle';
                case 'ACTIVE':
                    return 'fa-solid fa-play-circle';
                case 'RECOVERING':
                    return 'fa-solid fa-rotate';
                case 'STOPPING':
                    return 'fa-solid fa-pause-circle';
                case 'DEAD':
                    return 'fa-solid fa-stop-circle';
                case 'RELOAD':
                    return 'fa-solid fa-sync';
                case 'UNLOAD':
                    return 'fa-solid fa-trash';
                case 'GC':
                    return 'fa-solid fa-recycle';
                default:
                    return 'fa-solid fa-circle';
            }
        };

        const getTimelineEventTypeClass = (type) => {
            switch (type) {
                case 'READY':
                    return 'bg-blue-500/20 text-blue-400 px-2 py-0.5 rounded';
                case 'ACTIVE':
                    return 'bg-green-500/20 text-green-400 px-2 py-0.5 rounded';
                case 'RECOVERING':
                    return 'bg-cyan-500/20 text-cyan-400 px-2 py-0.5 rounded';
                case 'STOPPING':
                    return 'bg-amber-500/20 text-amber-400 px-2 py-0.5 rounded';
                case 'DEAD':
                    return 'bg-red-500/20 text-red-400 px-2 py-0.5 rounded';
                case 'RELOAD':
                    return 'bg-purple-500/20 text-purple-400 px-2 py-0.5 rounded';
                case 'UNLOAD':
                    return 'bg-pink-500/20 text-pink-400 px-2 py-0.5 rounded';
                case 'GC':
                    return 'bg-cyan-500/20 text-cyan-400 px-2 py-0.5 rounded';
                default:
                    return 'bg-slate-500/20 text-slate-400 px-2 py-0.5 rounded';
            }
        };

        // ==================== 生命周期 ====================
        // ==================== I18n ====================
        const locale = ref(localStorage.getItem('lingframe_locale') || 'zh-CN');
        const messages = ref({});
        const supportedLocales = {
            'zh-CN': '简体中文',
            'en-US': 'English'
        };

        const loadLocale = async (lang) => {
            try {
                const res = await fetch(`i18n/${lang}.json`);
                messages.value[lang] = await res.json();
            } catch (e) {
                console.error(`Failed to load locale ${lang}:`, e);
                // 回退为空对象或默认值
            }
        };

        const switchLocale = async (lang) => {
            if (!messages.value[lang]) {
                await loadLocale(lang);
            }
            locale.value = lang;
            localStorage.setItem('lingframe_locale', lang);
            document.documentElement.lang = lang;
            document.title = t('title');
        };

        const t = (key, params = {}, defaultVal = '') => {
            let actualParams = {};
            let defaultText = '';

            if (typeof params === 'string') {
                defaultText = params;
            } else {
                actualParams = params || {};
                defaultText = defaultVal;
            }

            const keys = key.split('.');
            let value = messages.value[locale.value];
            for (const k of keys) {
                if (value && value[k] !== undefined) {
                    value = value[k];
                } else {
                    return defaultText || key;
                }
            }
            // 替换形如 {n} 的参数占位符
            if (typeof value === 'string') {
                return value.replace(/\{(\w+)\}/g, (_, k) => actualParams[k] !== undefined ? actualParams[k] : `{${k}}`);
            }
            return value !== undefined ? value : (defaultText || key);
        };

        // ... 其余现有代码 ...

        // 获取性能指标
        const fetchPerformanceMetrics = async () => {
            try {
                const data = await api.get('/lings/metrics');
                if (data) {
                    // 保存前值（用于趋势箭头）
                    Object.keys(prevMetrics).forEach(k => {
                        prevMetrics[k] = perfMetrics[k];
                    });

                    perfMetrics.cpu = data.cpuUsage || 0;
                    perfMetrics.processCpuLoad = data.processCpuLoad || 0;
                    perfMetrics.memory = data.memoryUsage || 0;
                    perfMetrics.memoryUsed = data.memoryUsedMB || 0;
                    perfMetrics.memoryTotal = data.memoryTotalMB || 0;
                    perfMetrics.heapUsed = data.heapUsedMB || 0;
                    perfMetrics.heapMax = data.heapMaxMB || 0;
                    perfMetrics.heapUsage = data.heapUsage || 0;
                    perfMetrics.metaspaceUsed = data.metaspaceUsedKB || 0;
                    perfMetrics.metaspaceMax = data.metaspaceMaxKB || 0;
                    perfMetrics.metaspaceUsage = data.metaspaceUsage || 0;
                    perfMetrics.loadedClassCount = data.loadedClassCount || 0;
                    perfMetrics.totalLoadedClassCount = data.totalLoadedClassCount || 0;
                    perfMetrics.unloadedClassCount = data.unloadedClassCount || 0;
                    perfMetrics.threads = data.threadCount || 0;
                    perfMetrics.daemonThreads = data.daemonThreadCount || 0;
                    perfMetrics.peakThreads = data.peakThreadCount || 0;
                    perfMetrics.gcCount = data.gcCount || 0;
                    perfMetrics.gcTimeMs = data.gcTimeMs || 0;
                    perfMetrics.availableProcessors = data.availableProcessors || 0;
                    perfMetrics.systemLoadAverage = data.systemLoadAverage || 0;

                    // 更新 JVM 基础信息
                    if (data.jvmVersion) jvmInfo.version = data.jvmVersion;
                    if (data.jvmVendor) jvmInfo.vendor = data.jvmVendor;
                    if (data.osName) jvmInfo.osName = data.osName;
                    if (data.osArch) jvmInfo.osArch = data.osArch;
                    if (data.uptimeMs) jvmInfo.uptimeMs = data.uptimeMs;
                    if (data.pid) jvmInfo.pid = data.pid;

                    // 追加历史数据
                    const now = Date.now();
                    perfHistory.timestamps.push(now);
                    perfHistory.cpu.push(perfMetrics.cpu);
                    perfHistory.heapUsage.push(perfMetrics.heapUsage);
                    perfHistory.metaspaceUsage.push(perfMetrics.metaspaceUsage);
                    perfHistory.threads.push(perfMetrics.threads);
                    perfHistory.gcCount.push(perfMetrics.gcCount);
                    perfHistory.gcTimeMs.push(perfMetrics.gcTimeMs);
                    perfHistory.loadedClassCount.push(perfMetrics.loadedClassCount);

                    // 限制历史数据长度
                    if (perfHistory.timestamps.length > MAX_HISTORY_POINTS) {
                        const excess = perfHistory.timestamps.length - MAX_HISTORY_POINTS;
                        perfHistory.timestamps.splice(0, excess);
                        perfHistory.cpu.splice(0, excess);
                        perfHistory.heapUsage.splice(0, excess);
                        perfHistory.metaspaceUsage.splice(0, excess);
                        perfHistory.threads.splice(0, excess);
                        perfHistory.gcCount.splice(0, excess);
                        perfHistory.gcTimeMs.splice(0, excess);
                        perfHistory.loadedClassCount.splice(0, excess);
                    }

                    // 绘制折线图
                    drawMonitorCharts();
                }
            } catch (e) {
                console.warn('Failed to fetch metrics:', e.message);
            }
        };

        // 获取灵元健康指标
        const fetchLingHealthMetrics = async () => {
            try {
                const data = await api.get('/lings/health/all');
                if (data) {
                    Object.keys(lingHealthMetrics).forEach(lingId => {
                        if (!data[lingId]) {
                            delete lingHealthMetrics[lingId];
                        }
                    });
                    Object.keys(data).forEach(lingId => {
                        lingHealthMetrics[lingId] = data[lingId];
                    });
                }
            } catch (e) {
                console.warn('Failed to fetch ling health metrics:', e.message);
            }
        };

        const fetchLingGovernanceMetrics = async () => {
            try {
                const data = await api.get('/lings/governance/all');
                if (data) {
                    Object.keys(lingGovernanceMetrics).forEach(lingId => {
                        if (!data[lingId]) {
                            delete lingGovernanceMetrics[lingId];
                        }
                    });
                    Object.keys(data).forEach(lingId => {
                        lingGovernanceMetrics[lingId] = data[lingId];
                    });
                }
            } catch (e) {
                console.warn('Failed to fetch ling governance metrics:', e.message);
            }
        };

        const fetchRuntimeDiagnostics = async () => {
            try {
                const data = await api.get('/lings/metrics/runtime-diagnostics');
                if (data) {
                    Object.keys(runtimeDiagnostics).forEach(runtime => {
                        if (!data[runtime]) {
                            delete runtimeDiagnostics[runtime];
                        }
                    });
                    Object.keys(data).forEach(runtime => {
                        runtimeDiagnostics[runtime] = data[runtime];
                    });
                }
            } catch (e) {
                console.warn('Failed to fetch runtime diagnostics:', e.message);
            }
        };

        const fetchRuntimeGovernanceReadiness = async () => {
            try {
                const data = await api.get('/lings/metrics/runtime-governance-readiness');
                if (data) {
                    runtimeGovernanceReadiness.status = data.status || 'UNKNOWN';
                    runtimeGovernanceReadiness.summary = data.summary || '';
                    runtimeGovernanceReadiness.sharedApiBoundaryFrozen = !!data.sharedApiBoundaryFrozen;
                    runtimeGovernanceReadiness.diagnosticsCount = data.diagnosticsCount || 0;
                    runtimeGovernanceReadiness.blockers = Array.isArray(data.blockers) ? data.blockers : [];
                    runtimeGovernanceReadiness.warnings = Array.isArray(data.warnings) ? data.warnings : [];
                }
            } catch (e) {
                console.warn('Failed to fetch runtime governance readiness:', e.message);
            }
        };

        // ==================== 灵元星图 ====================
        // 计算星图节点位置（圆形布局）
        const getStarMapNodes = () => {
            const canvas = starMapCanvas.value;
            if (!canvas || lings.value.length === 0) return [];

            const w = canvas.clientWidth;
            const h = canvas.clientHeight;
            const cx = w / 2;
            const cy = h / 2;
            const count = lings.value.length;
            const radius = Math.min(cx, cy) - 50;

            return lings.value.map((ling, i) => {
                // 圆形均匀分布
                const angle = (2 * Math.PI * i / count) - Math.PI / 2;
                const x = cx + radius * Math.cos(angle);
                const y = cy + radius * Math.sin(angle);

                const health = lingHealthMetrics[ling.lingId]?.summary;
                const isActive = ling.status === 'ACTIVE' || ling.status === 'DEGRADED';
                const isCanary = (ling.versionDetails?.length || 0) >= 2;

                let color = '#475569'; // 默认灰
                let glowColor = 'rgba(71,85,105,0.3)';
                if (health) {
                    const status = health.healthStatus;
                    if (status === 'HEALTHY') { color = '#10b981'; glowColor = 'rgba(16,185,129,0.4)'; }
                    else if (status === 'WARNING') { color = '#f59e0b'; glowColor = 'rgba(245,158,11,0.4)'; }
                    else if (status === 'UNHEALTHY') { color = '#ef4444'; glowColor = 'rgba(239,68,68,0.4)'; }
                } else if (isActive) {
                    color = '#10b981'; glowColor = 'rgba(16,185,129,0.4)';
                }

                return {
                    lingId: ling.lingId,
                    x, y, color, glowColor,
                    isActive,
                    isCanary,
                    nodeRadius: isCanary ? 18 : 14,
                    shortName: getLingShortName(ling.lingId),
                    status: ling.status,
                    versionCount: ling.versionDetails?.length || 1
                };
            });
        };

        // 绘制星图
        const drawStarMap = () => {
            const canvas = starMapCanvas.value;
            if (!canvas) return;

            const ctx = canvas.getContext('2d');
            const dpr = window.devicePixelRatio || 1;
            const w = canvas.clientWidth;
            const h = canvas.clientHeight;
            canvas.width = w * dpr;
            canvas.height = h * dpr;
            ctx.scale(dpr, dpr);

            // 清空画布
            ctx.clearRect(0, 0, w, h);

            const nodes = getStarMapNodes();
            if (nodes.length === 0) return;

            const time = Date.now() / 1000;
            const cx = w / 2;
            const cy = h / 2;

            // 中心标识：零依赖
            ctx.strokeStyle = currentTheme.value === 'light' ? 'rgba(79, 70, 229, 0.15)' : 'rgba(99, 102, 241, 0.2)';
            ctx.lineWidth = 1;
            ctx.beginPath();
            ctx.arc(cx, cy, 24, 0, Math.PI * 2);
            ctx.stroke();
            ctx.fillStyle = currentTheme.value === 'light' ? 'rgba(79, 70, 229, 0.8)' : 'rgba(129, 140, 248, 0.7)';
            ctx.font = '11px -apple-system, system-ui, sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText('零依赖', cx, cy);

            // 1. 绘制依赖连线与流动微粒
            nodes.forEach((node, index) => {
                // 计算同步阻尼浮动偏移，使连线完美合拍
                let nx = node.x;
                let ny = node.y;
                if (node.isActive) {
                    const angle = (2 * Math.PI * index / nodes.length) - Math.PI / 2;
                    const floatOffset = 4 * Math.sin(time * 1.2 + index);
                    nx += Math.cos(angle) * floatOffset;
                    ny += Math.sin(angle) * floatOffset;
                }

                // 虚线网络
                ctx.strokeStyle = currentTheme.value === 'light' ? 'rgba(99, 102, 241, 0.12)' : 'rgba(99, 102, 241, 0.18)';
                ctx.lineWidth = 1.5;
                ctx.setLineDash([4, 4]);
                ctx.beginPath();
                ctx.moveTo(cx, cy);
                ctx.lineTo(nx, ny);
                ctx.stroke();
                ctx.setLineDash([]); // 还原实线

                // 数据流动微粒
                if (node.isActive) {
                    const pulseProgress = (time * 0.45 + index * 0.3) % 1;
                    const px = cx + (nx - cx) * pulseProgress;
                    const py = cy + (ny - cy) * pulseProgress;
                    
                    ctx.fillStyle = node.color;
                    ctx.beginPath();
                    ctx.arc(px, py, 3, 0, Math.PI * 2);
                    ctx.fill();

                    // 微粒光晕
                    const particleGlow = ctx.createRadialGradient(px, py, 0, px, py, 6);
                    particleGlow.addColorStop(0, node.glowColor);
                    particleGlow.addColorStop(1, 'rgba(0,0,0,0)');
                    ctx.fillStyle = particleGlow;
                    ctx.beginPath();
                    ctx.arc(px, py, 6, 0, Math.PI * 2);
                    ctx.fill();
                }
            });

            // 2. 绘制每个节点
            nodes.forEach((node, index) => {
                // 同步浮动偏移（使用局部变量，不修改原始坐标，避免累积漂移）
                let nx = node.x;
                let ny = node.y;
                if (node.isActive) {
                    const angle = (2 * Math.PI * index / nodes.length) - Math.PI / 2;
                    const floatOffset = 4 * Math.sin(time * 1.2 + index);
                    nx += Math.cos(angle) * floatOffset;
                    ny += Math.sin(angle) * floatOffset;
                }

                // 预警扩散雷达波纹
                if (node.status === 'DEGRADED' || node.status === 'ERROR') {
                    const radarTime = (time * 1.2 + index * 0.5) % 1;
                    const radarRadius = node.nodeRadius + radarTime * 20;
                    const radarOpacity = 1 - radarTime;
                    ctx.strokeStyle = node.status === 'DEGRADED' ? `rgba(245, 158, 11, ${radarOpacity * 0.7})` : `rgba(239, 68, 68, ${radarOpacity * 0.7})`;
                    ctx.lineWidth = 1.5;
                    ctx.beginPath();
                    ctx.arc(nx, ny, radarRadius, 0, Math.PI * 2);
                    ctx.stroke();
                }

                // 光晕效果
                const pulseScale = node.isActive ? 1 + 0.15 * Math.sin(time * 2 + index) : 1;
                const glowRadius = node.nodeRadius * 2.5 * pulseScale;

                const gradient = ctx.createRadialGradient(nx, ny, 0, nx, ny, glowRadius);
                gradient.addColorStop(0, node.glowColor);
                gradient.addColorStop(1, 'rgba(0,0,0,0)');
                ctx.fillStyle = gradient;
                ctx.beginPath();
                ctx.arc(nx, ny, glowRadius, 0, Math.PI * 2);
                ctx.fill();

                // 灰度灵元双环
                if (node.isCanary) {
                    ctx.strokeStyle = 'rgba(245,158,11,0.4)';
                    ctx.lineWidth = 2;
                    ctx.beginPath();
                    ctx.arc(nx, ny, node.nodeRadius + 5, 0, Math.PI * 2);
                    ctx.stroke();
                }

                // 主圆
                ctx.fillStyle = node.color;
                ctx.beginPath();
                ctx.arc(nx, ny, node.nodeRadius, 0, Math.PI * 2);
                ctx.fill();

                // 内部高光
                const innerGrad = ctx.createRadialGradient(
                    nx - node.nodeRadius * 0.3, ny - node.nodeRadius * 0.3, 0,
                    nx, ny, node.nodeRadius
                );
                innerGrad.addColorStop(0, 'rgba(255,255,255,0.2)');
                innerGrad.addColorStop(1, 'rgba(255,255,255,0)');
                ctx.fillStyle = innerGrad;
                ctx.beginPath();
                ctx.arc(nx, ny, node.nodeRadius, 0, Math.PI * 2);
                ctx.fill();

                // 名称标签
                const labelColor = currentTheme.value === 'light' ? '#1e293b' : '#e2e8f0';
                ctx.font = '11px -apple-system, system-ui, sans-serif';
                ctx.textAlign = 'center';
                ctx.textBaseline = 'alphabetic';
                // 亮色模式下为标签添加半透明背景衬底，避免连线交叉时文字难以辨识
                if (currentTheme.value === 'light') {
                    const textWidth = ctx.measureText(node.shortName).width;
                    const labelX = nx - textWidth / 2 - 3;
                    const labelY = ny + node.nodeRadius + 4;
                    ctx.fillStyle = 'rgba(255, 255, 255, 0.8)';
                    ctx.fillRect(labelX, labelY, textWidth + 6, 14);
                }
                ctx.fillStyle = labelColor;
                ctx.fillText(node.shortName, nx, ny + node.nodeRadius + 16);
            });

            // 尊重用户动画偏好：reduced-motion 时仅绘制一帧静态画面
            const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
            if (!prefersReducedMotion) {
                starMapAnimFrame = requestAnimationFrame(drawStarMap);
            }
        };

        // 星图点击事件
        const handleStarMapClick = (event) => {
            const canvas = starMapCanvas.value;
            if (!canvas) return;

            const rect = canvas.getBoundingClientRect();
            const x = event.clientX - rect.left;
            const y = event.clientY - rect.top;

            const nodes = getStarMapNodes();
            for (const node of nodes) {
                const dx = x - node.x;
                const dy = y - node.y;
                if (dx * dx + dy * dy <= (node.nodeRadius + 5) * (node.nodeRadius + 5)) {
                    selectLing(node.lingId);
                    return;
                }
            }
        };

        // 星图悬停事件
        const handleStarMapMouseMove = (event) => {
            const canvas = starMapCanvas.value;
            if (!canvas) return;

            const rect = canvas.getBoundingClientRect();
            const x = event.clientX - rect.left;
            const y = event.clientY - rect.top;

            const nodes = getStarMapNodes();
            let found = null;
            for (const node of nodes) {
                const dx = x - node.x;
                const dy = y - node.y;
                if (dx * dx + dy * dy <= (node.nodeRadius + 5) * (node.nodeRadius + 5)) {
                    found = node;
                    break;
                }
            }

            if (found) {
                starMapHover.value = {
                    x: event.clientX - rect.left,
                    y: event.clientY - rect.top,
                    name: found.shortName,
                    status: found.status,
                    versions: found.versionCount > 1 ? found.versionCount + ' versions' : ''
                };
                canvas.style.cursor = 'pointer';
            } else {
                starMapHover.value = null;
                canvas.style.cursor = 'default';
            }
        };

        const handleStarMapMouseLeave = () => {
            starMapHover.value = null;
        };

        // 监听导航切换到概览时启动星图
        watch(activeNav, (nav) => {
            if (nav === 'overview') {
                nextTick(() => {
                    if (starMapAnimFrame) cancelAnimationFrame(starMapAnimFrame);
                    drawStarMap();
                });
            } else {
                if (starMapAnimFrame) {
                    cancelAnimationFrame(starMapAnimFrame);
                    starMapAnimFrame = null;
                }
            }
        });

        // 监听灵元列表变化，更新首次发现时间和服务数量
        watch(lings, (newLings) => {
            const now = Date.now();
            newLings.forEach(ling => {
                if (!lingFirstSeen[ling.lingId]) {
                    lingFirstSeen[ling.lingId] = now;
                }
            });
            // 清理已移除灵元的记录
            Object.keys(lingFirstSeen).forEach(id => {
                if (!newLings.find(l => l.lingId === id)) {
                    delete lingFirstSeen[id];
                }
            });

            // 初次加载或灵元列表更新时，如果当前在概览页且星图尚未渲染（未启动动画帧），则触发渲染
            if (activeNav.value === 'overview' && !starMapAnimFrame && newLings.length > 0) {
                nextTick(() => {
                    if (starMapAnimFrame) cancelAnimationFrame(starMapAnimFrame);
                    drawStarMap();
                });
            }
        }, { deep: true });

        // 监听 playground 数据变化，更新服务数量缓存
        watch(playgroundServices, (services) => {
            if (activeId.value) {
                lingServiceCounts[activeId.value] = services ? services.length : 0;
            }
        });

        // 批量获取所有灵元的服务数量
        const fetchAllServiceCounts = async () => {
            for (const ling of lings.value) {
                if (lingServiceCounts[ling.lingId] === undefined) {
                    try {
                        const data = await api.get(`/playground/lings/${ling.lingId}/services`);
                        lingServiceCounts[ling.lingId] = data ? data.length : 0;
                    } catch (e) {
                        lingServiceCounts[ling.lingId] = 0;
                    }
                }
            }
        };

        // 灵元列表变化时，获取新灵元的服务数量
        watch(lings, (newLings) => {
            const hasNew = newLings.some(l => lingServiceCounts[l.lingId] === undefined);
            if (hasNew) {
                fetchAllServiceCounts();
            }
        });

        onMounted(async () => {
            applyTheme();
            updateTime();
            timeTimer = setInterval(updateTime, 1000);

            await loadLocale(locale.value);
            document.documentElement.lang = locale.value;
            nextTick(() => { document.title = t('title'); });

            // 先探测认证状态：用轻量请求检测是否需要 token
            try {
                await api.get('/lings');
                authenticated.value = true;
            } catch (e) {
                if (e.message === 'Unauthorized') {
                    // 已弹出登录框，等待用户输入后页面会 reload
                    return;
                }
                // 其他错误（如网络问题），继续加载
            }

            refreshLings();
            fetchPackages();
            connectSSE();

            updateEnvMode(currentEnv.value);

            fetchPerformanceMetrics();
            perfTimer = setInterval(fetchPerformanceMetrics, 3000);

            fetchLingHealthMetrics();
            fetchLingGovernanceMetrics();
            fetchRuntimeDiagnostics();
            fetchRuntimeGovernanceReadiness();
            healthTimer = setInterval(fetchLingHealthMetrics, 5000);
            governanceTimer = setInterval(fetchLingGovernanceMetrics, 5000);
            diagnosticsTimer = setInterval(fetchRuntimeDiagnostics, 5000);
            readinessTimer = setInterval(fetchRuntimeGovernanceReadiness, 5000);
        });

        // ==================== 监听环境切换 ====================
        watch(currentEnv, (newVal) => {
            updateEnvMode(newVal);
        });

        watch(activeLing, () => {
            syncIpcSwitch();
            syncInvocationForm();
        });

        // 监听 locale 变化，按需更新时间格式（可选）
        watch(locale, () => {
            updateTime();
        });

        watch(consoleExpanded, (newVal) => {
            if (newVal) {
                hasNewTraceAlert.value = false;
                nextTick(() => {
                    if (globalLogContainer.value) {
                        globalLogContainer.value.scrollTop = globalLogContainer.value.scrollHeight;
                    }
                });
            }
        });

        // 销毁所有 Chart 实例
        const destroyCharts = () => {
            Object.values(chartInstances).forEach(c => c.destroy());
            Object.keys(chartInstances).forEach(k => delete chartInstances[k]);
        };

        // 监听时间区间切换：长范围从后端查询，短范围用本地数据
        watch(chartTimeRange, () => {
            const longRanges = ['today', 'yesterday', '7d'];
            if (longRanges.includes(chartTimeRange.value)) {
                loadHistoryMetrics();
            } else {
                destroyCharts();
                nextTick(() => drawMonitorCharts());
            }
        });

        // 监听导航切换，切换到监控页时重绘图表
        watch(activeNav, (val) => {
            if (val === 'monitor') {
                destroyCharts();
                nextTick(() => drawMonitorCharts());
            } else if (val === 'lings') {
                fetchPackages();
            }
        });

        // 日志暂停恢复：批量追加缓存日志
        watch(logPaused, (paused) => {
            if (!paused && logPausedBuffer.length > 0) {
                logs.value.unshift(...logPausedBuffer);
                if (logs.value.length > 1000) {
                    logs.value = logs.value.slice(0, 1000);
                }
                logPausedBuffer.length = 0;
            }
        });

        const updateEnvMode = async (env) => {
            try {
                await api.post('/simulate/config/mode', { testEnv: env });

                const isProd = env === 'prod';
                const color = isProd ? 'success' : 'info';
                // toast 文案使用 i18n key
                const modeText = isProd ? t('toast.prodMode') : t('toast.devMode');

                showToast(t('toast.envSwitched', { mode: modeText }), color);
            } catch (e) {
                showToast(t('toast.envSwitchFailed') + ': ' + e.message, 'error');
            }
        };

        // 格式化运行时间
        const formatUptime = (ms) => {
            if (!ms) return '-';
            const s = Math.floor(ms / 1000);
            const d = Math.floor(s / 86400);
            const h = Math.floor((s % 86400) / 3600);
            const m = Math.floor((s % 3600) / 60);
            if (d > 0) return `${d}d ${h}h ${m}m`;
            if (h > 0) return `${h}h ${m}m`;
            return `${m}m`;
        };

        // 格式化服务演练场结果
        const formatPlaygroundResult = (result) => {
            if (result === null || result === undefined) return 'null';
            if (typeof result === 'string') return result;
            try {
                return JSON.stringify(result, null, 2);
            } catch {
                return String(result);
            }
        };

        // 绘制监控折线图
        const drawMonitorCharts = () => {
            if (activeNav.value !== 'monitor') return;
            if (typeof Chart === 'undefined') return;

            const rangeMs = { '5m': 300000, '15m': 900000, '30m': 1800000, '1h': 3600000, '3h': 10800000, 'today': 86400000, 'yesterday': 86400000, '7d': 604800000 }[chartTimeRange.value] || 1800000;
            const cutoff = Date.now() - rangeMs;
            const startIdx = perfHistory.timestamps.findIndex(t => t >= cutoff);
            if (startIdx < 0) return;

            const timestamps = perfHistory.timestamps.slice(startIdx);
            const labels = timestamps.map(ts => new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }));

            const isLight = currentTheme.value === 'light';
            const tooltipBg = isLight ? 'rgba(255,255,255,0.95)' : 'rgba(15,23,42,0.9)';
            const tooltipBorder = isLight ? '#cbd5e1' : '#334155';
            const tooltipTitle = isLight ? '#64748b' : '#94a3b8';
            const tooltipBody = isLight ? '#0f172a' : '#e2e8f0';
            const ticksColor = isLight ? 'rgba(15,23,42,0.6)' : 'rgba(148,163,184,0.5)';
            const gridColor = isLight ? 'rgba(15,23,42,0.08)' : 'rgba(100,116,139,0.1)';

            monitorCharts.value.forEach(chart => {
                const canvas = document.getElementById('chart-' + chart.key);
                if (!canvas) return;
                const data = perfHistory[chart.key].slice(startIdx);
                if (data.length < 2) return;

                // 复用或创建 Chart 实例
                let instance = chartInstances[chart.key];
                if (instance) {
                    instance.data.labels = labels;
                    instance.data.datasets[0].data = data;
                    instance.options.plugins.tooltip.backgroundColor = tooltipBg;
                    instance.options.plugins.tooltip.borderColor = tooltipBorder;
                    instance.options.plugins.tooltip.titleColor = tooltipTitle;
                    instance.options.plugins.tooltip.bodyColor = tooltipBody;
                    instance.options.scales.x.ticks.color = ticksColor;
                    instance.options.scales.x.grid.color = gridColor;
                    instance.options.scales.y.ticks.color = ticksColor;
                    instance.options.scales.y.grid.color = gridColor;
                    instance.update({ duration: 300, easing: "easeOutQuart" }); // 300ms 缓动过渡，平滑顺畅
                    return;
                }

                // 创建新实例
                const ctx = canvas.getContext('2d');
                const gradient = ctx.createLinearGradient(0, 0, 0, 120);
                gradient.addColorStop(0, chart.color + '40');
                gradient.addColorStop(1, chart.color + '05');

                chartInstances[chart.key] = new Chart(ctx, {
                    type: 'line',
                    data: {
                        labels: labels,
                        datasets: [{
                            data: data,
                            borderColor: chart.color,
                            backgroundColor: gradient,
                            borderWidth: 1.5,
                            fill: true,
                            pointRadius: 0,
                            pointHitRadius: 6,
                            tension: 0.3
                        }]
                    },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false,
                        animation: {
                            duration: 300,
                            easing: "easeOutQuart"
                        },
                        interaction: {
                            mode: 'index',
                            intersect: false
                        },
                        plugins: {
                            legend: { display: false },
                            tooltip: {
                                backgroundColor: tooltipBg,
                                titleColor: tooltipTitle,
                                bodyColor: tooltipBody,
                                borderColor: tooltipBorder,
                                borderWidth: 1,
                                padding: 8,
                                displayColors: false,
                                callbacks: {
                                    label: (ctx) => chart.isPercent ? ctx.parsed.y.toFixed(1) + '%' : ctx.parsed.y
                                }
                            }
                        },
                        scales: {
                            x: {
                                display: true,
                                ticks: {
                                    color: ticksColor,
                                    font: { size: 9 },
                                    maxTicksLimit: 6,
                                    maxRotation: 0
                                },
                                grid: { color: gridColor }
                            },
                            y: {
                                display: true,
                                min: chart.isPercent ? 0 : undefined,
                                max: chart.isPercent ? 100 : undefined,
                                ticks: {
                                    color: ticksColor,
                                    font: { size: 9 },
                                    maxTicksLimit: 5,
                                    callback: (v) => chart.isPercent ? v + '%' : v
                                },
                                grid: { color: gridColor }
                            }
                        }
                    }
                });
            });
        };

        // 页面不可见时暂停轮询，可见时恢复
        const handleVisibility = () => {
            if (document.hidden) {
                if (perfTimer) { clearInterval(perfTimer); perfTimer = null; }
                if (healthTimer) { clearInterval(healthTimer); healthTimer = null; }
                if (governanceTimer) { clearInterval(governanceTimer); governanceTimer = null; }
                if (diagnosticsTimer) { clearInterval(diagnosticsTimer); diagnosticsTimer = null; }
                if (readinessTimer) { clearInterval(readinessTimer); readinessTimer = null; }
            } else {
                if (!perfTimer) {
                    fetchPerformanceMetrics();
                    perfTimer = setInterval(fetchPerformanceMetrics, 3000);
                }
                if (!healthTimer) {
                    fetchLingHealthMetrics();
                    healthTimer = setInterval(fetchLingHealthMetrics, 5000);
                }
                if (!governanceTimer) {
                    fetchLingGovernanceMetrics();
                    governanceTimer = setInterval(fetchLingGovernanceMetrics, 5000);
                }
                if (!diagnosticsTimer) {
                    fetchRuntimeDiagnostics();
                    diagnosticsTimer = setInterval(fetchRuntimeDiagnostics, 5000);
                }
                if (!readinessTimer) {
                    fetchRuntimeGovernanceReadiness();
                    readinessTimer = setInterval(fetchRuntimeGovernanceReadiness, 5000);
                }
            }
        };
        document.addEventListener('visibilitychange', handleVisibility);

        // 清理定时器
        onUnmounted(() => {
            if (timeTimer) clearInterval(timeTimer);
            if (stressTimer) clearInterval(stressTimer);
            if (perfTimer) clearInterval(perfTimer);
            if (healthTimer) clearInterval(healthTimer);
            if (governanceTimer) clearInterval(governanceTimer);
            if (diagnosticsTimer) clearInterval(diagnosticsTimer);
            if (readinessTimer) clearInterval(readinessTimer);
            if (eventSource) eventSource.close();
            if (starMapAnimFrame) cancelAnimationFrame(starMapAnimFrame);
            destroyCharts();
            document.removeEventListener('visibilitychange', handleVisibility);
        });

        return {
            locale, supportedLocales, switchLocale, t,

            lings, activeId, activeNav, lingSearch, filteredLings, canaryPct, isAuto, ipcEnabled, ipcTarget,
            logs, lastAudit, logViewMode, logAggregationMode, logFilters, logContainer, isUserScrolling, logPaused, sidebarOpen,
            currentEnv, currentTime, sseStatus, sseStatusText,
            stats, loading, modal, toasts, envLabels, uploadModal, timelineModal, appState, authenticated, submitAuth,
            consoleHeight, autoScrollLogs, startConsoleResize,

            perfMetrics, jvmInfo, chartTimeRange, monitorCharts,
            lingHealthMetrics, lingGovernanceMetrics, runtimeDiagnostics, runtimeGovernanceReadiness, runtimeDiagnosticsList,
            invocationForm,

            activeLing, activeLingHealth, activeLingVersionHealth, activeLingGovernance, activeLingVersionGovernance, canCanary, canOperate, canActivate, canDeactivate, canRecover, displayLogs, availableVersions,

            refreshLings, selectLing, updateStatus, requestUnload,
            confirmModalAction, updateCanaryConfig, updateCanaryConfigLocally, resetCanary, togglePerm, toggleIpc,
            saveInvocationGovernance,
            simulate, simulateIPC, toggleAuto, resetStats, clearLogs,
            playgroundServices, playgroundLoading, playgroundInvoking, playgroundArgs, playgroundResult,
            expandedServices, toggleServiceExpand, isServiceExpanded,
            fetchPlaygroundServices, invokeService, getInvokeKey,
            isComplexParameterType, prefillJsonTemplate,
            handleLogScroll, scrollToTop, filterLogs, resetLogFilters,
            formatDrift, formatTime, formatSize, formatMetricNumber, formatBudgetPercent, formatBudgetValue, formatUptime, formatPlaygroundResult,
            getStatusClass, getLingShortName, getLingTagClass, getLingHealthDotClass, getLingUptime, getLingServiceCount, getLogColor, getTrend,
            getTimelineEventClass, getTimelineEventIcon, getTimelineEventTypeClass, getLingHealthStatusClass, getLingHealthRoleLabel, hasGovernanceSignals,
            starMapCanvas, starMapHover, handleStarMapClick, handleStarMapMouseMove, handleStarMapMouseLeave,
            openUploadModal, closeUploadModal, handleFileSelect, handleFileDrop, startUpload, doReloadLing, requestUnloadWithName, requestUnloadSpecific,
            openTimelineModal, closeTimelineModal, loadTimelineData,
            doUpdateStatus, fetchPerformanceMetrics, fetchLingHealthMetrics, fetchLingGovernanceMetrics, fetchRuntimeDiagnostics, fetchRuntimeGovernanceReadiness,
            uninstallResultModal, closeUninstallResultModal, getUninstallRiskLabel, getUninstallRiskClass, getUninstallTriggerLabel,

            currentTheme, toggleTheme, packages, fetchPackages, deployPackage, deletePackageFile, updateStatusForLing, updateCanaryWeight, getLingCanaryWeight,
            consoleExpanded, hasNewTraceAlert, globalLogContainer, onboardingSteps, packageSearch, filteredPackages
        };
    }
}).mount('#app');
