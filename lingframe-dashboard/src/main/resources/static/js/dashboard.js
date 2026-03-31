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
            onConfirm: null
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
        
        // 灵元健康指标
        const lingHealthMetrics = reactive({});

        const invocationForm = reactive({
            timeoutMs: '',
            rateLimitPerSecond: '',
            maxConcurrentThreads: ''
        });

        let eventSource = null;
        let timeTimer = null;
        let stressTimer = null;
        let perfTimer = null;
        let logIdCounter = 0;
        let toastIdCounter = 0;

        // 日志筛选和聚合相关
        const logAggregationMode = ref(false);
        const logFilters = reactive({
            version: '',
            eventType: '',
            keyword: ''
        });

        // ==================== 计算属性 ====================
        const activeLing = computed(() => lings.value.find(p => p.lingId === activeId.value));
        const canCanary = computed(() => (activeLing.value?.versionDetails?.length || 0) >= 2);
        const canOperate = computed(() => activeLing.value?.status === 'ACTIVE' || activeLing.value?.status === 'DEGRADED');
        const canActivate = computed(() => activeLing.value?.status === 'INACTIVE');
        const canDeactivate = computed(() => activeLing.value?.status === 'ACTIVE' || activeLing.value?.status === 'DEGRADED');
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
            }

            // 按版本筛选
            if (logFilters.version) {
                filteredLogs = filteredLogs.filter(l => l.version === logFilters.version);
            }

            // 按事件类型筛选
            if (logFilters.eventType) {
                filteredLogs = filteredLogs.filter(l => l.type === logFilters.eventType);
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
            
            doUpdateStatus(newStatus);
        };

        const requestUnload = () => {
            if (!activeLing.value) return;
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
                return;
            }

            modal.showVersionSelect = false;
            modal.versions = [];
            modal.selectedVersion = '';
            modal.onConfirm = async () => {
                modal.loading = true;
                try {
                    await api.delete(`/lings/uninstall/${activeId.value}`);
                    lings.value = lings.value.filter(p => p.lingId !== activeId.value);
                    activeId.value = null;
                    Object.assign(stats, { total: 0, v1: 0, v2: 0, v1Pct: 0, v2Pct: 0 });
                    showToast(t('toast.lingUnloaded'), 'success');
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

        const requestUnloadWithName = (lingId) => {
            const ling = lings.value.find(p => p.lingId === lingId);
            modal.title = t('modal.confirmUnload');
            modal.message = t('modal.unloadWarning', { lingId });
            modal.actionText = t('modal.unloadAction');
            modal.versionSelectLabel = t('modal.selectVersion');
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
                return;
            }

            modal.showVersionSelect = false;
            modal.versions = [];
            modal.selectedVersion = '';
            modal.onConfirm = async () => {
                modal.loading = true;
                try {
                    await api.delete(`/lings/uninstall/${lingId}`);
                    lings.value = lings.value.filter(p => p.lingId !== lingId);
                    if (activeId.value === lingId) {
                        activeId.value = null;
                        Object.assign(stats, { total: 0, v1: 0, v2: 0, v1Pct: 0, v2Pct: 0 }); // Reset stats
                    }
                    showToast(t('toast.lingUnloaded'), 'success');
                } catch (e) {
                    showToast(t('toast.unloadFailed') + ': ' + e.message, 'error');
                } finally {
                    modal.loading = false;
                    modal.show = false;
                }
            };
            modal.show = true;
        };

        const requestUnloadSpecific = (lingId, version) => {
            modal.title = t('modal.confirmUnload');
            modal.message = t('modal.unloadWarningSpecific', { lingId, version }) || `确认卸载服务 ${lingId} 的版本 ${version} 吗？`;
            modal.actionText = t('modal.unloadAction');
            modal.showVersionSelect = false; // 已指定版本，无需选择
            modal.onConfirm = async () => {
                modal.loading = true;
                try {
                    await api.delete(`/lings/uninstall/${lingId}/${version}`);
                    showToast(t('toast.lingVersionUnloaded', { version }) || `版本 ${version} 卸载成功`, 'success');
                    refreshLings();
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
        };

        const saveInvocationGovernance = async () => {
            if (!activeId.value) return;

            loading.invocation = true;
            try {
                const updated = await api.post(`/governance/${activeId.value}/invocation`, {
                    timeoutMs: normalizeNullableInt(invocationForm.timeoutMs),
                    rateLimitPerSecond: normalizeNullableInt(invocationForm.rateLimitPerSecond),
                    maxConcurrentThreads: normalizeNullableInt(invocationForm.maxConcurrentThreads)
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

        // ... 其余日志相关逻辑省略 ...

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
        // ... 其余 SSE 实现基本保持不变，日志内容为动态生成 ...

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
                version: data.version,
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
            // 替换形如 {n} 的参数占位符
            if (typeof value === 'string') {
                return value.replace(/\{(\w+)\}/g, (_, k) => params[k] !== undefined ? params[k] : `{${k}}`);
            }
            return value;
        };

        // ... 其余现有代码 ...

        // 获取性能指标
        const fetchPerformanceMetrics = async () => {
            try {
                const data = await api.get('/lings/metrics');
                if (data) {
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
                }
            } catch (e) {
                console.log('Failed to fetch metrics:', e.message);
            }
        };
        
        // 获取灵元健康指标
        const fetchLingHealthMetrics = async () => {
            try {
                const data = await api.get('/lings/health/all');
                if (data) {
                    Object.keys(data).forEach(lingId => {
                        lingHealthMetrics[lingId] = data[lingId];
                    });
                }
            } catch (e) {
                console.log('Failed to fetch ling health metrics:', e.message);
            }
        };

        onMounted(async () => {
            updateTime();
            timeTimer = setInterval(updateTime, 1000);

            await loadLocale(locale.value);
            document.documentElement.lang = locale.value;
            nextTick(() => { document.title = t('title'); });

            refreshLings();
            console.log(new Date(), 'start connecting sse')
            connectSSE();

            updateEnvMode(currentEnv.value);

            fetchPerformanceMetrics();
            perfTimer = setInterval(fetchPerformanceMetrics, 3000);
            
            fetchLingHealthMetrics();
            setInterval(fetchLingHealthMetrics, 5000);
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

        // 清理定时器
        onUnmounted(() => {
            if (timeTimer) clearInterval(timeTimer);
            if (stressTimer) clearInterval(stressTimer);
            if (perfTimer) clearInterval(perfTimer);
            if (eventSource) eventSource.close();
        });

        return {
            locale, supportedLocales, switchLocale, t,

            lings, activeId, canaryPct, isAuto, ipcEnabled, ipcTarget,
            logs, lastAudit, logViewMode, logAggregationMode, logFilters, logContainer, isUserScrolling, sidebarOpen,
            currentEnv, currentTime, sseStatus, sseStatusText,
            stats, loading, modal, toasts, envLabels, uploadModal, timelineModal,

            perfMetrics,
            lingHealthMetrics,
            invocationForm,

            activeLing, canCanary, canOperate, canActivate, canDeactivate, displayLogs, availableVersions,

            refreshLings, selectLing, updateStatus, requestUnload,
            confirmModalAction, updateCanaryConfig, updateCanaryConfigLocally, resetCanary, togglePerm, toggleIpc,
            saveInvocationGovernance,
            simulate, simulateIPC, toggleAuto, resetStats, clearLogs,
            handleLogScroll, scrollToTop, filterLogs, resetLogFilters,
            formatDrift, formatTime, formatSize,
            getStatusClass, getLingShortName, getLingTagClass, getLogColor,
            getTimelineEventClass, getTimelineEventIcon, getTimelineEventTypeClass,
            openUploadModal, closeUploadModal, handleFileSelect, handleFileDrop, startUpload, doReloadLing, requestUnloadWithName, requestUnloadSpecific,
            openTimelineModal, closeTimelineModal, loadTimelineData,
            doUpdateStatus, fetchPerformanceMetrics, fetchLingHealthMetrics
        };
    }
}).mount('#app');
