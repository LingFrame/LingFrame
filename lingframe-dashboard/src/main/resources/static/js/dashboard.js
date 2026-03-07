const { createApp, ref, reactive, computed, watch, onMounted, onUnmounted, nextTick } = Vue;

// API 配置
const API_BASE = '/lingframe/dashboard';

createApp({
    setup() {
        // ==================== 状态 ====================
        const lings = ref([]);
        const activeId = ref(null);
        const canaryPct = ref(0);
        const isAuto = ref(false);
        const ipcEnabled = ref(true);
        const ipcTarget = ref('user-ling');
        const logs = ref([]);
        const lastAudit = ref(null);
        const logViewMode = ref('current');
        const logContainer = ref(null);
        const isUserScrolling = ref(false);
        const sidebarOpen = ref(false);
        const currentEnv = ref('dev');
        const currentTime = ref('');
        const sseStatus = ref('disconnected');
        const toasts = ref([]);

        const stats = reactive({ total: 0, v1: 0, v2: 0, v1Pct: 0, v2Pct: 0 });

        const loading = reactive({
            lings: false,
            status: false,
            canary: false,
            permissions: false,
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
            onConfirm: null
        });

        const uploadModal = reactive({
            show: false,
            file: null,
            isDragging: false,
            uploading: false,
            progress: 0
        });

        const envLabels = { dev: '开发', test: '测试', prod: '生产' };

        let eventSource = null;
        let timeTimer = null;
        let stressTimer = null;
        let logIdCounter = 0;
        let toastIdCounter = 0;

        // ==================== 计算属性 ====================
        const activeLing = computed(() => lings.value.find(p => p.lingId === activeId.value));
        const canCanary = computed(() => activeLing.value?.versions?.length >= 2);
        const canOperate = computed(() => activeLing.value?.status === 'ACTIVE' || activeLing.value?.status === 'DEGRADED');
        const sseStatusText = computed(() => ({
            connected: t('sidebar.sseConnected'),
            connecting: t('sidebar.sseConnecting'),
            disconnected: t('sidebar.sseDisconnected')
        }[sseStatus.value]));

        const displayLogs = computed(() => {
            if (logViewMode.value === 'current' && activeId.value) {
                return logs.value.filter(l => l.lingId === activeId.value);
            }
            return logs.value;
        });

        // ==================== Toast 通知 ====================
        const showToast = (message, type = 'info') => {
            const id = ++toastIdCounter;
            toasts.value.push({ id, message, type });
            setTimeout(() => {
                toasts.value = toasts.value.filter(t => t.id !== id);
            }, 3000);
        };

        // ==================== API 调用 ====================
        const api = {
            async get(path) {
                const res = await fetch(API_BASE + path);
                const data = await res.json();
                if (!data.success) throw new Error(data.message);
                return data.data;
            },
            async post(path, body = {}) {
                const res = await fetch(API_BASE + path, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                const data = await res.json();
                if (!data.success) throw new Error(data.message);
                return data.data;
            },
            async delete(path, body = {}) {
                const res = await fetch(API_BASE + path, {
                    method: 'DELETE',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
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
            // ... (Same logic, no strings to change inside normally) ...
            if (isAuto.value) {
                toggleAuto(); // 停止压测
            }
            activeId.value = lingId;
            const ling = lings.value.find(p => p.lingId === lingId);
            if (ling) {
                canaryPct.value = ling.canaryPercent || 0;
            }
            // 重置统计
            Object.assign(stats, { total: 0, v1: 0, v2: 0, v1Pct: 0, v2Pct: 0 });
            lastAudit.value = null;

            // 设置 IPC 目标为其他灵元
            const otherLing = lings.value.find(p => p.lingId !== lingId && p.status === 'ACTIVE');
            if (otherLing) {
                ipcTarget.value = otherLing.lingId;
            }

            // 同步 IPC 开关状态
            syncIpcSwitch();
        };

        const updateStatus = async (newStatus) => {
            if (!activeId.value) return;
            loading.status = true;
            try {
                const updated = await api.post(`/lings/${activeId.value}/status`, { status: newStatus });
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

        const requestUnload = () => {
            if (!activeLing.value) return;
            modal.title = t('modal.confirmUnload');
            modal.message = t('modal.unloadWarning', { lingId: activeId.value });
            modal.actionText = t('modal.unloadAction');
            modal.showVersionSelect = true;
            modal.versions = activeLing.value.versions || [];
            modal.selectedVersion = ''; // 默认全量卸载

            modal.onConfirm = async () => {
                modal.loading = true;
                try {
                    let url = `/lings/uninstall/${activeId.value}`;
                    if (modal.selectedVersion) {
                        url += `/${modal.selectedVersion}`;
                    }

                    await api.delete(url);

                    if (modal.selectedVersion && modal.versions.length > 1) {
                        // 仅仅是删除了某个版本，刷新部分信息即可
                        showToast(t('toast.lingVersionUnloaded', { version: modal.selectedVersion }) || `版本 ${modal.selectedVersion} 卸载成功`, 'success');
                        refreshLings(); // 简单起见，重新拉取最新状态
                    } else {
                        // 全量删除 或 最后一个版本被删除
                        lings.value = lings.value.filter(p => p.lingId !== activeId.value);
                        activeId.value = null;
                        Object.assign(stats, { total: 0, v1: 0, v2: 0, v1Pct: 0, v2Pct: 0 });
                        showToast(t('toast.lingUnloaded'), 'success');
                    }
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

        const startUpload = async () => {
            if (!uploadModal.file) return;

            uploadModal.uploading = true;
            uploadModal.progress = 0;

            // 模拟进度条 (因为 fetch API 不支持原生上传进度)
            const progressTimer = setInterval(() => {
                if (uploadModal.progress < 90) {
                    uploadModal.progress += Math.floor(Math.random() * 10) + 1;
                }
            }, 200);

            try {
                const formData = new FormData();
                formData.append('file', uploadModal.file);

                const res = await fetch(API_BASE + '/lings/install', {
                    method: 'POST',
                    body: formData
                });
                const data = await res.json();

                clearInterval(progressTimer);
                uploadModal.progress = 100;

                if (!data.success) throw new Error(data.message);

                showToast(t('toast.installSuccess'), 'success');
                closeUploadModal();
                refreshLings(); // 刷新列表
            } catch (e) {
                clearInterval(progressTimer);
                uploadModal.progress = 0;
                showToast(t('toast.installFailed') + ': ' + e.message, 'error');
            } finally {
                uploadModal.uploading = false;
            }
        };

        const reloadLing = async (lingId) => {
            loading.lings = true; // 复用 lings loading
            try {
                await api.post(`/lings/${lingId}/reload`);
                showToast(t('toast.reloadSuccess'), 'success');
                refreshLings();
            } catch (e) {
                showToast(t('toast.reloadFailed') + ': ' + e.message, 'error');
            } finally {
                loading.lings = false;
            }
        };

        const requestUnloadWithName = (lingId) => {
            const ling = lings.value.find(p => p.lingId === lingId);
            modal.title = t('modal.confirmUnload');
            modal.message = t('modal.unloadWarning', { lingId });
            modal.actionText = t('modal.unloadAction');
            modal.showVersionSelect = true;
            modal.versions = ling ? (ling.versions || []) : [];
            modal.selectedVersion = ''; // 默认全量卸载

            modal.onConfirm = async () => {
                modal.loading = true;
                try {
                    let url = `/lings/uninstall/${lingId}`;
                    if (modal.selectedVersion) {
                        url += `/${modal.selectedVersion}`;
                    }

                    await api.delete(url);

                    if (modal.selectedVersion && modal.versions.length > 1) {
                        showToast(t('toast.lingVersionUnloaded', { version: modal.selectedVersion }) || `版本 ${modal.selectedVersion} 卸载成功`, 'success');
                        refreshLings();
                    } else {
                        lings.value = lings.value.filter(p => p.lingId !== lingId);
                        if (activeId.value === lingId) {
                            activeId.value = null;
                            Object.assign(stats, { total: 0, v1: 0, v2: 0, v1Pct: 0, v2Pct: 0 }); // Reset stats
                        }
                        showToast(t('toast.lingUnloaded'), 'success');
                    }
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
                    canaryVersion: activeLing.value?.canaryVersion
                });
                showToast(t('toast.canarySet', { percent: canaryPct.value }), 'success');
            } catch (e) {
                showToast(t('toast.canaryFailed') + ': ' + e.message, 'error');
            } finally {
                loading.canary = false;
            }
        };

        // ==================== 权限操作 ====================
        const togglePerm = async (perm) => {
            if (!activeLing.value) return;

            // ... (logs skipped for brevity) ...

            const currentPerms = activeLing.value.permissions || {};
            const currentValue = currentPerms[perm] !== false;
            const newValue = !currentValue;

            // 构建新的权限状态
            const newPerms = {
                dbRead: currentPerms.dbRead !== false,
                dbWrite: currentPerms.dbWrite !== false,
                cacheRead: currentPerms.cacheRead !== false,
                cacheWrite: currentPerms.cacheWrite !== false,
                ipcServices: currentPerms.ipcServices || [],
                [perm]: newValue
            };

            // 权限级联逻辑
            if (perm === 'dbWrite' && newValue) {
                newPerms.dbRead = true;
            }
            if (perm === 'cacheWrite' && newValue) {
                newPerms.cacheRead = true;
            }

            if (perm === 'dbRead' && !newValue) {
                newPerms.dbWrite = false;
            }
            if (perm === 'cacheRead' && !newValue) {
                newPerms.cacheWrite = false;
            }

            loading.permissions = true;
            try {
                await api.post(`/governance/${activeId.value}/permissions`, newPerms);
                const idx = lings.value.findIndex(p => p.lingId === activeId.value);
                if (idx !== -1) {
                    lings.value[idx].permissions = newPerms;
                }

                // 改进提示信息，说明级联效果
                let message = newValue ? t('toast.permEnabled', { perm }) : t('toast.permDisabled', { perm });
                if (perm === 'dbWrite' && newValue && !currentPerms.dbRead) {
                    message += t('toast.alsoEnabled', { perm: 'dbRead' });
                } else if (perm === 'cacheWrite' && newValue && !currentPerms.cacheRead) {
                    message += t('toast.alsoEnabled', { perm: 'cacheRead' });
                } else if (perm === 'dbRead' && !newValue && currentPerms.dbWrite) {
                    message += t('toast.alsoDisabled', { perm: 'dbWrite' });
                } else if (perm === 'cacheRead' && !newValue && currentPerms.cacheWrite) {
                    message += t('toast.alsoDisabled', { perm: 'cacheWrite' });
                }

                showToast(message, 'success');
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
            // ... (Toggle logic) ...
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
                // ... copy perms ...
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
                        stats.v1Pct = stats.total > 0 ? ((stats.v1 / stats.total) * 100).toFixed(1) : 0;
                        stats.v2Pct = stats.total > 0 ? ((stats.v2 / stats.total) * 100).toFixed(1) : 0;
                    } catch (e) {
                        console.error('Stress test error', e);
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
            Object.assign(stats, { total: 0, v1: 0, v2: 0, v1Pct: 0, v2Pct: 0 });
            lastAudit.value = null;
        };

        // ==================== SSE 日志流 ====================
        const connectSSE = () => {
            if (eventSource) {
                eventSource.close();
            }

            sseStatus.value = 'connecting';
            eventSource = new EventSource(API_BASE + '/stream');

            eventSource.onopen = () => {
                sseStatus.value = 'connected';
                console.log(new Date(), 'SSE connected');
            };
            // ... (Rest of SSE implementation stays mostly same, log content is dynamic)

            // 🔥 添加通用消息监听器
            eventSource.onmessage = (e) => {
                console.log('SSE onmessage:', e);
            };

            eventSource.addEventListener('log-event', (e) => {
                console.log('SSE log-event received:', e.data);  // 🔥 调试
                try {
                    const data = JSON.parse(e.data);
                    console.log('Parsed data:', data);  // 🔥 调试
                    addLog(data);
                } catch (err) {
                    console.warn('Failed to parse log event', err);
                }
            });

            eventSource.addEventListener('ping', () => {
                // 心跳
                console.log('SSE ping received');  // 🔥 调试
            });

            eventSource.onerror = () => {
                sseStatus.value = 'disconnected';
                console.log('SSE disconnected, reconnecting...');
                setTimeout(connectSSE, 3000);
            };
        };

        const addLog = (data) => {
            const log = {
                id: ++logIdCounter,
                traceId: data.traceId,
                lingId: data.lingId,
                content: data.content,
                type: data.type,
                tag: data.tag,
                depth: data.depth || 0,
                timestamp: data.timestamp
            };

            logs.value.unshift(log);
            if (logs.value.length > 1000) {
                logs.value.pop();
            }

            // 自动滚动
            if (!isUserScrolling.value && logContainer.value) {
                nextTick(() => {
                    logContainer.value.scrollTop = 0;
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

        const formatTime = (ts) => {
            if (!ts) return '--:--:--';
            const d = new Date(ts);
            return d.toLocaleTimeString(locale.value === 'zh-CN' ? 'zh-CN' : 'en-US', { hour12: false });
        };

        const getStatusClass = (status) => ({
            'ACTIVE': 'status-active',
            'INACTIVE': 'status-loaded',
            'DEGRADED': 'status-error',
            'REMOVED': 'status-unloaded',
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
                // Fallback to empty object or default
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

        const t = (key, params = {}) => {
            const keys = key.split('.');
            let value = messages.value[locale.value];
            for (const k of keys) {
                if (value && value[k]) {
                    value = value[k];
                } else {
                    return key;
                }
            }
            // Replace params like {n}
            if (typeof value === 'string') {
                return value.replace(/\{(\w+)\}/g, (_, k) => params[k] !== undefined ? params[k] : `{${k}}`);
            }
            return value;
        };

        // ... Existing code ...

        onMounted(async () => {
            updateTime();
            timeTimer = setInterval(updateTime, 1000);

            // Load initial locale
            await loadLocale(locale.value);
            document.documentElement.lang = locale.value;
            // Delay title update slightly to ensure messages are loaded
            nextTick(() => { document.title = t('title'); });

            refreshLings();
            console.log(new Date(), 'start connecting sse')
            connectSSE();

            // 初始化同步
            updateEnvMode(currentEnv.value);
        });

        // ==================== 监听环境切换 ====================
        watch(currentEnv, (newVal) => {
            updateEnvMode(newVal);
        });

        // Watch locale change to update time format if needed (optional)
        watch(locale, () => {
            updateTime();
        });

        const updateEnvMode = async (env) => {
            try {
                await api.post('/simulate/config/mode', { testEnv: env });

                const isProd = env === 'prod';
                const color = isProd ? 'success' : 'info';
                // Use keys for toast
                const modeText = isProd ? t('toast.prodMode') : t('toast.devMode');

                showToast(t('toast.envSwitched', { mode: modeText }), color);
            } catch (e) {
                showToast(t('toast.envSwitchFailed') + ': ' + e.message, 'error');
            }
        };

        // ... (keep onUnmounted)

        return {
            // I18n
            locale, supportedLocales, switchLocale, t,

            // 状态
            lings, activeId, canaryPct, isAuto, ipcEnabled, ipcTarget,
            logs, lastAudit, logViewMode, logContainer, isUserScrolling, sidebarOpen,
            currentEnv, currentTime, sseStatus, sseStatusText,
            stats, loading, modal, toasts, envLabels, uploadModal,

            // 计算属性
            activeLing, canCanary, canOperate, displayLogs,

            // 方法
            refreshLings, selectLing, updateStatus, requestUnload,
            confirmModalAction, updateCanaryConfig, togglePerm, toggleIpc,
            simulate, simulateIPC, toggleAuto, resetStats, clearLogs,
            handleLogScroll, scrollToTop,
            formatDrift, formatTime, formatSize,
            getStatusClass, getLingShortName, getLingTagClass, getLogColor,
            openUploadModal, closeUploadModal, handleFileSelect, handleFileDrop, startUpload, reloadLing, requestUnloadWithName
        };
    }
}).mount('#app');
