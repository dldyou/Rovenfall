import {
  Activity,
  AlertTriangle,
  ArrowLeft,
  ArrowRight,
  Box,
  Check,
  ChevronRight,
  CircleDollarSign,
  Clipboard,
  Coins,
  FileClock,
  HelpCircle,
  KeyRound,
  LandPlot,
  Languages,
  LoaderCircle,
  LogOut,
  Menu,
  Pickaxe,
  RefreshCw,
  Search,
  Server,
  ShieldCheck,
  Store,
  UserRoundCog,
  Users,
  X,
  type LucideIcon,
} from 'lucide-react';
import { useCallback, useEffect, useId, useRef, useState } from 'react';

import {
  ApiError,
  apiRequest,
  queryString,
  registerReadOnlyTools,
  type ActionPreview,
  type AuditRow,
  type ClaimRow,
  type DashboardData,
  type OperationResult,
  type PageData,
  type PlayerDetail,
  type PlayerRow,
  type SearchScope,
  type ShopRow,
  type TransactionRow,
  type ViewId,
} from '../src/api';
import { messages, type CopyKey, type Locale } from '../src/i18n';

const TOKEN_KEY = 'rovenfall-admin-token';
const locales: Locale[] = ['ko', 'en', 'ja'];
const localeTags: Record<Locale, string> = { ko: 'ko-KR', en: 'en-US', ja: 'ja-JP' };

type Translator = ReturnType<typeof messages>;
type ActionType = 'set_role' | 'grant_balance' | 'debit_balance' | 'reverse';

interface ActionDraft {
  type: ActionType;
  playerId: string;
  role: string;
  amount: string;
  originalTransactionId: string;
  compensation: string;
  reason: string;
}

interface AuditFilterState {
  player: string;
  action: string;
  outcome: string;
  from: string;
  to: string;
}

const emptyAction: ActionDraft = {
  type: 'set_role',
  playerId: '',
  role: 'viewer',
  amount: '',
  originalTransactionId: '',
  compensation: 'none',
  reason: '',
};

const emptyAuditFilters: AuditFilterState = {
  player: '',
  action: '',
  outcome: '',
  from: '',
  to: '',
};

const navigation: Array<{ id: ViewId; icon: LucideIcon }> = [
  { id: 'dashboard', icon: Activity },
  { id: 'players', icon: Users },
  { id: 'audit', icon: FileClock },
  { id: 'transactions', icon: Coins },
  { id: 'claims', icon: LandPlot },
  { id: 'shops', icon: Store },
];

const viewDescription: Record<ViewId, CopyKey> = {
  dashboard: 'dashboardDescription',
  players: 'playersDescription',
  audit: 'auditDescription',
  transactions: 'transactionsDescription',
  claims: 'claimsDescription',
  shops: 'shopsDescription',
};

export default function Home() {
  const [locale, setLocale] = useState<Locale>(() => {
    const saved = localStorage.getItem('rovenfall-admin-locale');
    return locales.includes(saved as Locale) ? saved as Locale : 'ko';
  });
  const [token, setToken] = useState(() => sessionStorage.getItem(TOKEN_KEY) ?? '');
  const t = messages(locale);

  useEffect(() => {
    document.documentElement.lang = locale;
    localStorage.setItem('rovenfall-admin-locale', locale);
  }, [locale]);

  const connect = (value: string) => {
    sessionStorage.setItem(TOKEN_KEY, value);
    setToken(value);
  };
  const disconnect = useCallback(() => {
    sessionStorage.removeItem(TOKEN_KEY);
    setToken('');
  }, []);

  if (!token) {
    return <Login locale={locale} setLocale={setLocale} t={t} onConnect={connect} />;
  }
  return (
    <Console
      token={token}
      locale={locale}
      setLocale={setLocale}
      t={t}
      onDisconnect={disconnect}
    />
  );
}

function Login({
  locale,
  setLocale,
  t,
  onConnect,
}: {
  locale: Locale;
  setLocale: (value: Locale) => void;
  t: Translator;
  onConnect: (token: string) => void;
}) {
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(event: React.SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = draft.trim();
    if (value.length < 32) {
      setError(locale === 'ko' ? '유효한 관리 토큰을 입력하세요.' : 'Enter a valid administration token.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await apiRequest<DashboardData>(value, '/api/v1/dashboard');
      onConnect(value);
    } catch (reason) {
      setError(errorMessage(reason, locale));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login-page">
      <div className="login-grid" aria-hidden="true" />
      <section className="login-panel block-panel">
        <div className="brand-mark large"><Pickaxe /></div>
        <span className="eyebrow"><ShieldCheck /> ROVENFALL · {t.localOnly}</span>
        <h1>{t.loginTitle}</h1>
        <p className="login-copy">{t.loginBody}</p>
        <form onSubmit={submit} className="login-form">
          <label htmlFor="admin-token">{t.token}</label>
          <div className="input-with-icon">
            <KeyRound />
            <input
              id="admin-token"
              type="password"
              autoComplete="off"
              spellCheck={false}
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              placeholder={t.tokenPlaceholder}
            />
          </div>
          {error && <p className="form-error" role="alert">{error}</p>}
          <button className="pixel-button primary wide" disabled={busy} type="submit">
            {busy ? <LoaderCircle className="spin" /> : <Server />}
            {busy ? t.connecting : t.connect}
          </button>
        </form>
        <p className="secure-note"><ShieldCheck /> {t.secureNote}</p>
        <LocalePicker locale={locale} setLocale={setLocale} />
      </section>
    </main>
  );
}

function Console({
  token,
  locale,
  setLocale,
  t,
  onDisconnect,
}: {
  token: string;
  locale: Locale;
  setLocale: (value: Locale) => void;
  t: Translator;
  onDisconnect: () => void;
}) {
  const [active, setActive] = useState<ViewId>('dashboard');
  const [mobileMenu, setMobileMenu] = useState(false);
  const [dashboard, setDashboard] = useState<DashboardData | null>(null);
  const [pageData, setPageData] = useState<PageData | null>(null);
  const [queryDraft, setQueryDraft] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [auditDraft, setAuditDraft] = useState(emptyAuditFilters);
  const [auditFilters, setAuditFilters] = useState(emptyAuditFilters);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [refreshRevision, setRefreshRevision] = useState(0);
  const [selectedPlayer, setSelectedPlayer] = useState<string | null>(null);
  const [actionDraft, setActionDraft] = useState<ActionDraft | null>(null);
  const [helpOpen, setHelpOpen] = useState(false);
  const [toast, setToast] = useState('');
  const [toastTransaction, setToastTransaction] = useState('');
  const searchRef = useRef<HTMLInputElement>(null);

  const handleFailure = useCallback((reason: unknown) => {
    if (reason instanceof ApiError && reason.status === 401) {
      onDisconnect();
      return;
    }
    setError(errorMessage(reason, locale));
  }, [locale, onDisconnect]);

  useEffect(() => registerReadOnlyTools(token, handleFailure), [token, handleFailure]);

  useEffect(() => {
    const timeout = window.setTimeout(() => {
      setPage(0);
      setQuery(queryDraft.trim());
    }, 280);
    return () => window.clearTimeout(timeout);
  }, [queryDraft]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      if (active === 'dashboard') {
        const value = await apiRequest<DashboardData>(token, '/api/v1/dashboard');
        setDashboard(value);
        setPageData(null);
      } else if (active === 'audit') {
        const value = await apiRequest<PageData<AuditRow>>(token, `/api/v1/audit?${queryString({
          query: query || undefined,
          player: auditFilters.player || undefined,
          action: auditFilters.action || undefined,
          outcome: auditFilters.outcome || undefined,
          from: auditFilters.from ? new Date(auditFilters.from).getTime() : undefined,
          to: auditFilters.to ? new Date(auditFilters.to).getTime() : undefined,
          page,
          pageSize: 25,
        })}`);
        setPageData(value);
      } else {
        const value = await apiRequest<PageData>(token, `/api/v1/search?${queryString({
          scope: active as SearchScope,
          query: query || '*',
          page,
          pageSize: 25,
        })}`);
        setPageData(value);
      }
    } catch (reason) {
      handleFailure(reason);
    } finally {
      setLoading(false);
    }
  }, [active, auditFilters, handleFailure, page, query, token]);

  useEffect(() => {
    const timeout = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timeout);
  }, [load, refreshRevision]);

  useEffect(() => {
    if (!toast) return;
    const timeout = window.setTimeout(() => { setToast(''); setToastTransaction(''); }, 8_000);
    return () => window.clearTimeout(timeout);
  }, [toast]);

  const switchView = useCallback((view: ViewId) => {
    setActive(view);
    setPage(0);
    setPageData(null);
    setLoading(true);
    setError('');
    setMobileMenu(false);
  }, []);

  function applyAuditFilters(filters: AuditFilterState) {
    setAuditDraft(filters);
    setAuditFilters(filters);
    setPage(0);
  }

  function viewPlayerAudit(playerId: string) {
    setSelectedPlayer(null);
    setQueryDraft('');
    setQuery('');
    applyAuditFilters({ ...emptyAuditFilters, player: playerId });
    switchView('audit');
  }

  useEffect(() => {
    const listener = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      const editing = target?.matches('input, textarea, select, [contenteditable="true"]') ?? false;
      if (event.key === 'Escape') {
        if (helpOpen || actionDraft || selectedPlayer) return;
        if (mobileMenu) { event.preventDefault(); setMobileMenu(false); }
        else if (active !== 'dashboard') { event.preventDefault(); switchView('dashboard'); }
        return;
      }
      if (helpOpen || actionDraft || selectedPlayer) return;
      if ((event.ctrlKey && event.key.toLowerCase() === 'k') || (!editing && event.key === '/')) {
        event.preventDefault(); searchRef.current?.focus(); searchRef.current?.select(); return;
      }
      if (editing) return;
      if (event.altKey && /^[1-6]$/.test(event.key)) {
        event.preventDefault();
        const destination = navigation[Number(event.key) - 1]?.id;
        if (destination) switchView(destination);
      } else if (event.key.toLowerCase() === 'r' && !event.ctrlKey && !event.metaKey) {
        event.preventDefault(); setRefreshRevision((value) => value + 1);
      } else if (event.key === '?') {
        event.preventDefault(); setHelpOpen(true);
      }
    };
    window.addEventListener('keydown', listener);
    return () => window.removeEventListener('keydown', listener);
  }, [active, actionDraft, helpOpen, mobileMenu, selectedPlayer, switchView]);

  function searchKey(event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter' && active === 'dashboard' && queryDraft.trim()) {
      switchView('players');
    }
  }

  return (
    <main className="console-shell">
      <Sidebar active={active} mobileOpen={mobileMenu} t={t} onSelect={switchView} />
      {mobileMenu && <button className="menu-scrim" aria-label="Close menu" onClick={() => setMobileMenu(false)} />}
      <section className="workspace">
        <header className="topbar">
          <button className="icon-button mobile-only" onClick={() => setMobileMenu(true)} aria-label="Open menu"><Menu /></button>
          <div className="search-field">
            <Search />
            <span className="search-scope" aria-hidden="true">{active === 'dashboard' ? t.allRecords : t[active]}</span>
            <input
              ref={searchRef}
              value={queryDraft}
              onChange={(event) => setQueryDraft(event.target.value)}
              onKeyDown={searchKey}
              placeholder={t.searchPlaceholder}
              aria-label={t.search}
            />
            {queryDraft && <button onClick={() => setQueryDraft('')} aria-label="Clear"><X /></button>}
          </div>
          <div className="topbar-actions">
            <span className="connection-chip"><span /> {t.connected}</span>
            <button className="icon-button" onClick={() => setHelpOpen(true)} title={`${t.help} (?)`} aria-label={t.help}><HelpCircle /></button>
            <LocalePicker locale={locale} setLocale={setLocale} compact />
            <button className="icon-button" onClick={onDisconnect} title={t.logout}><LogOut /></button>
          </div>
        </header>

        <div className="content">
          <section className="page-heading">
            <div>
              <nav className="breadcrumbs" aria-label={t.currentLocation}>
                <button onClick={() => switchView('dashboard')}>ROVENFALL</button><ChevronRight /><span>{t[active]}</span>
              </nav>
              <h1>{active === 'dashboard' ? t.headline : t[active]}</h1>
              <p>{t[viewDescription[active]]}</p>
              <div className="shortcut-strip" aria-label={t.shortcuts}>
                <span><kbd>Esc</kbd>{t.goBack}</span><span><kbd>Ctrl K</kbd>{t.focusSearch}</span>
                <span><kbd>R</kbd>{t.refresh}</span><span><kbd>?</kbd>{t.help}</span>
              </div>
            </div>
            <div className="heading-actions">
              <button className="pixel-button" onClick={() => setRefreshRevision((value) => value + 1)} disabled={loading}>
                <RefreshCw className={loading ? 'spin' : ''} /> {t.refresh}
              </button>
              <button className="pixel-button primary" onClick={() => setActionDraft({ ...emptyAction })}>
                <UserRoundCog /> {t.manage}
              </button>
            </div>
          </section>

          {active === 'audit' && (
            <AuditFilters
              t={t}
              draft={auditDraft}
              setDraft={setAuditDraft}
              activeCount={Object.values(auditFilters).filter(Boolean).length}
              apply={applyAuditFilters}
            />
          )}

          {error ? (
            <ErrorState message={error} retry={() => void load()} t={t} />
          ) : active === 'dashboard' ? (
            <Dashboard dashboard={dashboard} loading={loading} locale={locale} t={t} onPlayer={setSelectedPlayer} onSelect={switchView} />
          ) : (
            <DataView
              active={active}
              data={pageData}
              loading={loading}
              locale={locale}
              t={t}
              onPlayer={setSelectedPlayer}
              onReverse={(transaction) => setActionDraft({
                ...emptyAction,
                type: 'reverse',
                playerId: transaction.playerId,
                originalTransactionId: transaction.transactionId,
              })}
              onPage={setPage}
            />
          )}
        </div>
      </section>

      {selectedPlayer && (
        <PlayerDialog token={token} playerId={selectedPlayer} locale={locale} t={t}
          onClose={() => setSelectedPlayer(null)}
          onAudit={viewPlayerAudit}
          onManage={(playerId) => { setSelectedPlayer(null); setActionDraft({ ...emptyAction, playerId }); }} />
      )}
      {actionDraft && (
        <ActionDialog
          token={token}
          draft={actionDraft}
          setDraft={setActionDraft}
          t={t}
          locale={locale}
          onClose={() => setActionDraft(null)}
          onComplete={(result) => {
            setActionDraft(null);
            setToast(`${t.actionDone} ${shortId(result.transactionId)}`);
            setToastTransaction(result.transactionId);
            setRefreshRevision((value) => value + 1);
          }}
        />
      )}
      {helpOpen && <HelpDialog t={t} onClose={() => setHelpOpen(false)} />}
      {toast && <output className="toast"><Check /><span>{toast}</span>{toastTransaction && <button onClick={() => {
        setQueryDraft(toastTransaction); switchView('audit'); setToast(''); setToastTransaction('');
      }}>{t.viewAudit}</button>}</output>}
    </main>
  );
}

function Sidebar({ active, mobileOpen, t, onSelect }: {
  active: ViewId;
  mobileOpen: boolean;
  t: Translator;
  onSelect: (view: ViewId) => void;
}) {
  return (
    <aside className={`sidebar ${mobileOpen ? 'open' : ''}`}>
      <div className="brand">
        <div className="brand-mark"><Pickaxe /></div>
        <div><strong>ROVENFALL</strong><span>{t.console}</span></div>
      </div>
      <nav aria-label="Administration">
        {navigation.map(({ id, icon: Icon }, index) => (
          <button key={id} className={active === id ? 'active' : ''} onClick={() => onSelect(id)}>
            <Icon /><span>{t[id]}</span><kbd>Alt {index + 1}</kbd>{active === id && <ChevronRight className="nav-arrow" />}
          </button>
        ))}
      </nav>
      <div className="bridge-card">
        <div><Server /><span>{t.localOnly}</span></div>
        <strong><i />{window.location.host}</strong>
        <small>{t.connected}</small>
      </div>
    </aside>
  );
}

function Dashboard({ dashboard, loading, locale, t, onPlayer, onSelect }: {
  dashboard: DashboardData | null;
  loading: boolean;
  locale: Locale;
  t: Translator;
  onPlayer: (id: string) => void;
  onSelect: (view: ViewId) => void;
}) {
  if (loading && !dashboard) return <LoadingState t={t} />;
  if (!dashboard) return null;
  const metrics = [
    { label: t.onlinePlayers, value: dashboard.onlinePlayers, detail: `${t.total} ${dashboard.knownPlayers}`, icon: Users, tone: 'emerald', target: 'players' as const },
    { label: t.recentTransactions, value: dashboard.recentTransactions, detail: `${formatNumber(dashboard.recentVolume, locale)} G`, icon: Coins, tone: 'gold', target: 'transactions' as const },
    { label: t.activeClaims, value: dashboard.claims, detail: `${t.activeShops} ${dashboard.shops}`, icon: LandPlot, tone: 'spruce', target: 'claims' as const },
    { label: t.warnings, value: dashboard.recentAlerts, detail: `${t.denied} ${dashboard.recentDenied}`, icon: AlertTriangle, tone: 'redstone', target: 'audit' as const },
  ];
  return (
    <div className="dashboard-stack">
      <section className="metric-grid">
        {metrics.map(({ label, value, detail, icon: Icon, tone, target }) => (
          <button className={`metric-card block-panel ${tone}`} key={label} onClick={() => onSelect(target)}>
            <div className="metric-icon"><Icon /></div>
            <span>{label}</span>
            <strong>{formatNumber(value, locale)}</strong>
            <small>{detail}</small><ChevronRight className="metric-arrow" />
          </button>
        ))}
      </section>
      <section className="dashboard-grid">
        <div className="block-panel data-panel">
          <PanelTitle icon={FileClock} title={t.recentAudit} detail={formatDate(dashboard.generatedAt, locale)} />
          <AuditTable rows={dashboard.recentAudit} locale={locale} t={t} onPlayer={onPlayer} compact />
        </div>
        <aside className="status-stack">
          <div className={`block-panel status-card ${dashboard.writable ? 'healthy' : 'danger'}`}>
            <ShieldCheck />
            <div><span>{t.status}</span><strong>{dashboard.writable ? t.serverWritable : t.serverReadOnly}</strong></div>
          </div>
          <div className="block-panel world-card">
            <span className="eyebrow"><CircleDollarSign /> {t.recentVolume}</span>
            <strong>{formatNumber(dashboard.recentVolume, locale)} G</strong>
            <div className="ore-line"><i /><i /><i /><i /><i /></div>
            <small>{dashboard.serverTimeZone}</small>
          </div>
        </aside>
      </section>
    </div>
  );
}

function AuditFilters(props: {
  t: Translator;
  draft: AuditFilterState;
  setDraft: (value: AuditFilterState) => void;
  activeCount: number;
  apply: (value: AuditFilterState) => void;
}) {
  const { t } = props;
  const update = (field: keyof AuditFilterState, value: string) =>
    props.setDraft({ ...props.draft, [field]: value });
  const recentDenied = () => props.apply({
    ...emptyAuditFilters,
    outcome: 'denied',
    from: datetimeLocalValue(Date.now() - 86_400_000),
  });
  return (
    <form className="filter-bar block-panel" onSubmit={(event) => { event.preventDefault(); props.apply(props.draft); }}>
      <div className="filter-presets">
        <strong>{t.quickFilters}</strong>
        <button type="button" onClick={recentDenied}><FileClock />{t.recentDenied}</button>
        <button type="button" onClick={() => props.apply(emptyAuditFilters)}><X />{t.clearFilters}</button>
        <span>{t.activeFilters}: {props.activeCount}</span>
      </div>
      <label>{t.playerFilter}<input value={props.draft.player} onChange={(event) => update('player', event.target.value)} placeholder="name / UUID" /></label>
      <label>{t.actionFilter}<input value={props.draft.action} onChange={(event) => update('action', event.target.value)} placeholder="rovenfall:…" /></label>
      <label>{t.outcome}<select value={props.draft.outcome} onChange={(event) => update('outcome', event.target.value)}>
        <option value="">{t.all}</option><option value="success">{t.success}</option>
        <option value="denied">{t.deniedResult}</option><option value="failed">{t.failed}</option><option value="no_change">{t.noChange}</option>
      </select></label>
      <label>{t.from}<input type="datetime-local" value={props.draft.from} onChange={(event) => update('from', event.target.value)} /></label>
      <label>{t.to}<input type="datetime-local" value={props.draft.to} onChange={(event) => update('to', event.target.value)} /></label>
      <button className="pixel-button" type="submit"><Search /> {t.apply}</button>
    </form>
  );
}

function DataView({ active, data, loading, locale, t, onPlayer, onReverse, onPage }: {
  active: ViewId;
  data: PageData | null;
  loading: boolean;
  locale: Locale;
  t: Translator;
  onPlayer: (id: string) => void;
  onReverse: (row: TransactionRow) => void;
  onPage: (page: number) => void;
}) {
  if (loading && !data) return <LoadingState t={t} />;
  return (
    <section className="block-panel data-panel full">
      <PanelTitle icon={navigation.find((item) => item.id === active)?.icon ?? FileClock} title={t[active]}
        detail={data ? `${t.total} ${formatNumber(data.totalEntries, locale)}` : ''} />
      {loading ? <div className="table-loading"><LoaderCircle className="spin" /></div> : !data?.entries.length ? (
        <div className="empty-state"><Box /><strong>{t.empty}</strong></div>
      ) : active === 'players' ? (
        <PlayerTable rows={data.entries as PlayerRow[]} locale={locale} t={t} onPlayer={onPlayer} />
      ) : active === 'audit' ? (
        <AuditTable rows={data.entries as AuditRow[]} locale={locale} t={t} onPlayer={onPlayer} />
      ) : active === 'transactions' ? (
        <TransactionTable rows={data.entries as TransactionRow[]} locale={locale} t={t} onPlayer={onPlayer} onReverse={onReverse} />
      ) : active === 'claims' ? (
        <ClaimTable rows={data.entries as ClaimRow[]} locale={locale} t={t} onPlayer={onPlayer} />
      ) : (
        <ShopTable rows={data.entries as ShopRow[]} t={t} />
      )}
      {data && <Pagination data={data} t={t} onPage={onPage} />}
    </section>
  );
}

function PlayerTable({ rows, locale, t, onPlayer }: { rows: PlayerRow[]; locale: Locale; t: Translator; onPlayer: (id: string) => void }) {
  return <div className="table-wrap"><table><thead><tr><th>{t.name}</th><th>{t.role}</th><th>{t.balance}</th><th>{t.career}</th><th>{t.claimCount}</th><th>{t.lastSeen}</th></tr></thead>
    <tbody>{rows.map((row) => <tr key={row.playerId}>
      <td><PlayerCell id={row.playerId} name={row.name} onClick={() => onPlayer(row.playerId)} /></td><td><RoleBadge role={row.role} /></td>
      <td className="number gold-text">{formatNumber(row.balance, locale)} G</td><td>{humanize(row.activeCareer || '—')}</td>
      <td className="number">{row.claims}</td><td>{formatDate(row.lastSeen, locale)}</td>
    </tr>)}</tbody></table></div>;
}

function AuditTable({ rows, locale, t, onPlayer, compact = false }: { rows: AuditRow[]; locale: Locale; t: Translator; onPlayer: (id: string) => void; compact?: boolean }) {
  if (!rows.length) return <div className="empty-state small"><Box /><strong>{t.empty}</strong></div>;
  return <div className="table-wrap"><table><thead><tr><th>{t.time}</th><th>{t.action}</th><th>{t.actor}</th><th>{t.target}</th>{!compact && <th>{t.reason}</th>}<th>{t.result}</th></tr></thead>
    <tbody>{rows.map((row) => <tr key={`${row.timestamp}-${row.transactionId}`}>
      <td className="time-cell">{formatDate(row.timestamp, locale)}</td><td><code>{humanize(row.action)}</code></td>
      <td><PlayerLink id={row.actorId} name={row.actorName} onPlayer={onPlayer} /></td>
      <td className="truncate-cell" title={row.target}>{row.target}</td>{!compact && <td className="truncate-cell" title={row.reason}>{row.reason}</td>}
      <td><OutcomeBadge outcome={row.outcome} t={t} /></td>
    </tr>)}</tbody></table></div>;
}

function TransactionTable({ rows, locale, t, onPlayer, onReverse }: { rows: TransactionRow[]; locale: Locale; t: Translator; onPlayer: (id: string) => void; onReverse?: (row: TransactionRow) => void }) {
  return <div className="table-wrap"><table><thead><tr><th>{t.time}</th><th>{t.kind}</th><th>{t.name}</th><th>{t.amount}</th><th>{t.transactionId}</th><th>{t.status}</th><th aria-label={t.action} /></tr></thead>
    <tbody>{rows.map((row) => <tr key={row.transactionId}>
      <td className="time-cell">{formatDate(row.timestamp, locale)}</td><td><code>{humanize(row.kind)}</code></td>
      <td><button className="table-link" onClick={() => onPlayer(row.playerId)}>{row.playerName || shortId(row.playerId)}</button></td>
      <td className="number gold-text">{formatNumber(row.amount, locale)} G</td><td><code title={row.transactionId}>{shortId(row.transactionId)}</code></td>
      <td>{row.reversedBy ? <span className="status-badge neutral">reversed</span> : <span className="status-badge success">active</span>}</td>
      <td>{onReverse && <button className="row-action" disabled={Boolean(row.reversedBy)} onClick={() => onReverse(row)}>{t.reverse}</button>}</td>
    </tr>)}</tbody></table></div>;
}

function ClaimTable({ rows, locale, t, onPlayer }: { rows: ClaimRow[]; locale: Locale; t: Translator; onPlayer: (id: string) => void }) {
  return <div className="table-wrap"><table><thead><tr><th>{t.location}</th><th>{t.owner}</th><th>{t.price}</th><th>{t.trusted}</th><th>{t.status}</th></tr></thead>
    <tbody>{rows.map((row) => <tr key={row.key}><td><code>{row.dimension}</code><small className="subcell">chunk {row.chunkX}, {row.chunkZ}</small></td>
      <td><button className="table-link" onClick={() => onPlayer(row.ownerId)}>{row.ownerName || shortId(row.ownerId)}</button></td>
      <td className="number gold-text">{formatNumber(row.purchasePrice, locale)} G</td><td className="number">{row.trustedPlayers}</td>
      <td>{row.pendingTransferTo ? <span className="status-badge warning">transfer</span> : <span className="status-badge success">active</span>}</td></tr>)}</tbody></table></div>;
}

function ShopTable({ rows, t }: { rows: ShopRow[]; t: Translator }) {
  return <div className="table-wrap"><table><thead><tr><th>Shop ID</th><th>{t.template}</th><th>{t.location}</th><th>{t.offers}</th><th>Range</th></tr></thead>
    <tbody>{rows.map((row) => <tr key={row.shopId}><td><code>{row.shopId}</code></td><td><code>{row.templateId}</code></td>
      <td>{row.dimension || '—'}<small className="subcell">{row.position}</small></td><td className="number">{row.offers}</td><td className="number">{row.maxDistance}m</td></tr>)}</tbody></table></div>;
}

function Pagination({ data, t, onPage }: { data: PageData; t: Translator; onPage: (page: number) => void }) {
  return <footer className="pagination"><span>{t.total} {data.totalEntries} · {t.page} {data.totalPages ? data.page + 1 : 0}/{data.totalPages}</span>
    <div><button disabled={data.page <= 0} onClick={() => onPage(data.page - 1)}><ArrowLeft />{t.previous}</button>
      <button disabled={data.page + 1 >= data.totalPages} onClick={() => onPage(data.page + 1)}>{t.next}<ArrowRight /></button></div></footer>;
}

function PlayerDialog({ token, playerId, locale, t, onClose, onAudit, onManage }: {
  token: string; playerId: string; locale: Locale; t: Translator; onClose: () => void;
  onAudit: (id: string) => void; onManage: (id: string) => void;
}) {
  const [data, setData] = useState<PlayerDetail | null>(null);
  const [error, setError] = useState('');
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    void apiRequest<PlayerDetail>(token, `/api/v1/players/${encodeURIComponent(playerId)}`)
      .then(setData).catch((reason) => setError(errorMessage(reason, locale)));
  }, [locale, playerId, token]);
  return <Modal title={t.details} closeLabel={t.close} onClose={onClose} wide>
    {error ? <p className="form-error">{error}</p> : !data ? <LoadingState t={t} compact /> : <div className="player-detail">
      <div className="player-hero"><div className="player-avatar">{(data.name || '?').slice(0, 1).toUpperCase()}</div><div>
        <span className={`status-badge ${data.online ? 'success' : 'neutral'}`}>{data.online ? t.online : t.offline}</span>
        <h2>{data.name || shortId(data.playerId)}</h2><button className="copy-line" onClick={() => { void navigator.clipboard.writeText(data.playerId); setCopied(true); }}><code>{data.playerId}</code><Clipboard /> {copied ? t.copied : t.copyId}</button>
      </div><div className="player-actions"><button className="pixel-button" onClick={() => onAudit(data.playerId)}><FileClock />{t.viewPlayerAudit}</button>
        <button className="pixel-button primary" onClick={() => onManage(data.playerId)}><UserRoundCog />{t.manage}</button></div></div>
      <div className="detail-grid"><Detail label={t.role} value={humanize(data.role)} /><Detail label={t.balance} value={`${formatNumber(data.balance, locale)} G`} gold />
        <Detail label={t.career} value={humanize(data.activeCareer || '—')} /><Detail label={t.claimCount} value={String(data.claimCount)} />
        <Detail label="XP" value={formatNumber(data.activityExperience, locale)} /><Detail label={t.lastSeen} value={formatDate(data.lastSeen, locale)} /></div>
      <h3>{t.recentTransactions}</h3><TransactionTable rows={data.transactions.slice(0, 5)} locale={locale} t={t} onPlayer={() => undefined} />
    </div>}
  </Modal>;
}

function ActionDialog({ token, draft, setDraft, t, locale, onClose, onComplete }: {
  token: string; draft: ActionDraft; setDraft: (value: ActionDraft | null) => void; t: Translator; locale: Locale;
  onClose: () => void; onComplete: (result: OperationResult) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [preview, setPreview] = useState<ActionPreview | null>(null);
  const [confirmation, setConfirmation] = useState('');
  const update = (field: keyof ActionDraft, value: string) => setDraft({ ...draft, [field]: value });
  function payload() {
    const value: Record<string, string> = { type: draft.type, reason: draft.reason.trim() };
    if (draft.type === 'set_role') Object.assign(value, { playerId: draft.playerId.trim(), role: draft.role });
    if (draft.type === 'grant_balance' || draft.type === 'debit_balance') Object.assign(value, { playerId: draft.playerId.trim(), amount: draft.amount.trim() });
    if (draft.type === 'reverse') Object.assign(value, { originalTransactionId: draft.originalTransactionId.trim(), compensation: draft.compensation });
    return value;
  }
  async function submitPreview(event: React.SyntheticEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError('');
    try {
      const result = await apiRequest<ActionPreview>(token, '/api/v1/actions/preview', { method: 'POST', body: JSON.stringify(payload()) });
      setPreview(result); setConfirmation('');
    } catch (reason) { setError(errorMessage(reason, locale)); } finally { setBusy(false); }
  }
  async function confirm() {
    if (!preview) return;
    setBusy(true); setError('');
    try {
      const result = await apiRequest<OperationResult>(token, '/api/v1/actions/confirm', {
        method: 'POST', body: JSON.stringify({ previewId: preview.previewId, confirmation }),
      });
      onComplete(result);
    } catch (reason) {
      setError(errorMessage(reason, locale));
      if (reason instanceof ApiError && ['PREVIEW_NOT_FOUND', 'PREVIEW_EXPIRED', 'STALE_PREVIEW'].includes(reason.code)) setPreview(null);
    } finally { setBusy(false); }
  }
  const details = preview?.details;
  const target = details?.playerName || details?.playerId || details?.originalTransactionId || '—';
  return <Modal title={preview ? t.operationPreview : t.manage} closeLabel={t.close} onClose={onClose}
    closeOnEscape={!preview} closeOnBackdrop={!preview} showCloseButton={!preview}>
    {preview && details ? <div className="action-review">
      <p className="review-intro"><ShieldCheck />{t.previewBody}</p>
      <dl className="review-grid">
        <div><dt>{t.selectAction}</dt><dd>{humanize(String(details.type))}</dd></div>
        <div><dt>{t.target}</dt><dd>{String(target)}</dd></div>
        <div><dt>{t.beforeValue}</dt><dd>{String(details.beforeValue ?? '—')}</dd></div>
        <div><dt>{t.afterValue}</dt><dd>{String(details.afterValue ?? '—')}</dd></div>
        <div className="wide"><dt>{t.reason}</dt><dd>{String(details.reason)}</dd></div>
        <div className="wide"><dt>{t.generatedTransaction}</dt><dd><code>{preview.transactionId}</code></dd></div>
        <div className={details.onlineRequired ? undefined : 'wide'}><dt>{t.expires}</dt><dd>{formatDate(preview.expiresAt, locale)}</dd></div>
        {Boolean(details.onlineRequired) && <div><dt>{t.targetStatus}</dt><dd className={details.online ? 'success-text' : 'danger-text'}>{details.online ? t.online : t.offline}</dd></div>}
      </dl>
      {preview.requiresTypedConfirmation && <label className="confirmation-field">{t.confirmationPhrase}<input data-autofocus required autoComplete="off" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} placeholder="EXECUTE" /></label>}
      <p className="danger-note"><AlertTriangle />{t.irreversible}</p>
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="review-actions"><button className="pixel-button" onClick={onClose}>{t.cancel}</button><button className="pixel-button" onClick={() => { setPreview(null); setError(''); }}>{t.edit}</button>
        <button className="pixel-button primary" disabled={busy || preview.requiresTypedConfirmation && confirmation.trim().toLowerCase() !== 'execute'} onClick={() => void confirm()}>{busy ? <LoaderCircle className="spin" /> : <ShieldCheck />}{busy ? t.executing : t.confirmChange}</button></div>
    </div> : <form className="action-form" onSubmit={submitPreview}>
      <label>{t.selectAction}<select value={draft.type} onChange={(event) => update('type', event.target.value)}>
        <option value="set_role">{t.setRole}</option><option value="grant_balance">{t.grant}</option><option value="debit_balance">{t.debit}</option><option value="reverse">{t.reverse}</option>
      </select></label>
      {draft.type !== 'reverse' && <label>{t.playerUuid}<input required value={draft.playerId} onChange={(event) => update('playerId', event.target.value)} placeholder="00000000-0000-0000-0000-000000000000" /></label>}
      {draft.type === 'set_role' && <label>{t.roleLabel}<select value={draft.role} onChange={(event) => update('role', event.target.value)}>
        <option value="viewer">viewer</option><option value="moderator">moderator</option><option value="economy_manager">economy_manager</option><option value="content_manager">content_manager</option><option value="owner">owner</option>
      </select></label>}
      {(draft.type === 'grant_balance' || draft.type === 'debit_balance') && <label>{t.amountLabel}<input required inputMode="numeric" pattern="[0-9]+" value={draft.amount} onChange={(event) => update('amount', event.target.value)} placeholder="1000" /></label>}
      {draft.type === 'reverse' && <><label>{t.originalTransaction}<input required value={draft.originalTransactionId} onChange={(event) => update('originalTransactionId', event.target.value)} placeholder="00000000-0000-0000-0000-000000000000" /></label>
        <label>Compensation<select value={draft.compensation} onChange={(event) => update('compensation', event.target.value)}><option value="none">none</option><option value="refund_without_items_or_stock">refund_without_items_or_stock</option></select></label></>}
      <label>{t.reason}<textarea required maxLength={256} value={draft.reason} onChange={(event) => update('reason', event.target.value)} placeholder={t.reasonPlaceholder} /></label>
      <p className="danger-note"><AlertTriangle />{t.irreversible}</p>
      {error && <p className="form-error" role="alert">{error}</p>}
      <button className="pixel-button primary wide" disabled={busy} type="submit">{busy ? <LoaderCircle className="spin" /> : <ShieldCheck />}{busy ? t.reviewing : t.reviewChange}</button>
    </form>
    }
  </Modal>;
}

function HelpDialog({ t, onClose }: { t: Translator; onClose: () => void }) {
  const shortcuts = [['Ctrl K /', t.focusSearch], ['Alt 1–6', t.switchSection], ['Esc', t.goBack], ['R', t.refreshData], ['?', t.openShortcutHelp]];
  return <Modal title={t.shortcutHelp} closeLabel={t.close} onClose={onClose}>
    <div className="help-content">
      <p>{t.shortcutIntro}</p>
      <div className="shortcut-list">{shortcuts.map(([key, label]) => <div key={key}><kbd>{key}</kbd><span>{label}</span></div>)}</div>
      <section className="workflow-card"><h3>{t.dailyWorkflow}</h3><ol><li>{t.workflowSearch}</li><li>{t.workflowReview}</li><li>{t.workflowAudit}</li></ol></section>
      <p className="safe-note"><ShieldCheck />{t.safeOperation}</p>
      <button className="pixel-button wide" data-autofocus onClick={onClose}>{t.close}</button>
    </div>
  </Modal>;
}

function Modal({ title, closeLabel, onClose, children, wide = false, closeOnEscape = true, closeOnBackdrop = true, showCloseButton = true }: {
  title: string; closeLabel: string; onClose: () => void; children: React.ReactNode; wide?: boolean;
  closeOnEscape?: boolean; closeOnBackdrop?: boolean; showCloseButton?: boolean;
}) {
  const titleId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const closeRef = useRef(onClose);
  useEffect(() => { closeRef.current = onClose; }, [onClose]);
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const dialog = dialogRef.current;
    const focusable = () => Array.from(dialog?.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [href], [tabindex]:not([tabindex="-1"])') ?? []).filter((element) => element.offsetParent !== null);
    (dialog?.querySelector<HTMLElement>('[data-autofocus]') ?? focusable()[0])?.focus();
    const listener = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && closeOnEscape) { event.preventDefault(); event.stopImmediatePropagation(); closeRef.current(); return; }
      if (event.key !== 'Tab') return;
      const elements = focusable(); if (!elements.length) return;
      const first = elements[0]; const last = elements[elements.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    window.addEventListener('keydown', listener);
    return () => { window.removeEventListener('keydown', listener); previous?.focus(); };
  }, [closeOnEscape]);
  return <div className="modal-layer" role="presentation" onMouseDown={(event) => {
    if (closeOnBackdrop && event.target === event.currentTarget) closeRef.current();
  }}>
    <dialog ref={dialogRef} open className={`modal block-panel ${wide ? 'wide' : ''}`} aria-modal="true" aria-labelledby={titleId}>
      <header><h2 id={titleId}>{title}</h2>{showCloseButton && <button className="icon-button" onClick={onClose} aria-label={closeLabel}><X /></button>}</header>{children}
    </dialog></div>;
}

function PanelTitle({ icon: Icon, title, detail }: { icon: LucideIcon; title: string; detail?: string }) {
  return <header className="panel-title"><div><Icon /><h2>{title}</h2></div>{detail && <span>{detail}</span>}</header>;
}

function PlayerCell({ id, name, onClick }: { id: string; name: string; onClick: () => void }) {
  return <button className="player-cell" onClick={onClick}><span className="mini-avatar">{(name || '?').slice(0, 1).toUpperCase()}</span><span><strong>{name || shortId(id)}</strong><code>{shortId(id)}</code></span></button>;
}

function PlayerLink({ id, name, onPlayer }: { id: string; name: string; onPlayer: (id: string) => void }) {
  if (id === '00000000-0000-0000-0000-000000000000') return <span>{name || 'SYSTEM'}</span>;
  return <button className="table-link" onClick={() => onPlayer(id)}>{name || shortId(id)}</button>;
}

function RoleBadge({ role }: { role: string }) { return <span className={`role-badge ${role}`}>{humanize(role)}</span>; }
function OutcomeBadge({ outcome, t }: { outcome: AuditRow['outcome']; t: Translator }) {
  const label = outcome === 'success' ? t.success : outcome === 'failed' ? t.failed : outcome === 'no_change' ? t.noChange : t.deniedResult;
  return <span className={`status-badge ${outcome}`}>{label}</span>;
}
function Detail({ label, value, gold = false }: { label: string; value: string; gold?: boolean }) { return <div className="detail-item"><span>{label}</span><strong className={gold ? 'gold-text' : ''}>{value}</strong></div>; }

function LocalePicker({ locale, setLocale, compact = false }: { locale: Locale; setLocale: (value: Locale) => void; compact?: boolean }) {
  const next = locales[(locales.indexOf(locale) + 1) % locales.length];
  return <button type="button" className={compact ? 'locale-button compact' : 'locale-button'} onClick={() => setLocale(next)} title="Language">
    <Languages /> {locale.toUpperCase()}
  </button>;
}

function LoadingState({ t, compact = false }: { t: Translator; compact?: boolean }) { return <div className={`loading-state ${compact ? 'compact' : ''}`}><LoaderCircle className="spin" /><span>{t.loading}</span></div>; }
function ErrorState({ message, retry, t }: { message: string; retry: () => void; t: Translator }) { return <div className="error-state block-panel"><AlertTriangle /><h2>{message}</h2><button className="pixel-button" onClick={retry}><RefreshCw />{t.retry}</button></div>; }

function formatNumber(value: string | number, locale: Locale): string {
  try { return BigInt(value).toLocaleString(localeTags[locale]); } catch { return String(value); }
}
function formatDate(value: number, locale: Locale): string {
  if (!value) return '—';
  return new Intl.DateTimeFormat(localeTags[locale], { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(value);
}
function datetimeLocalValue(value: number): string {
  const date = new Date(value);
  return new Date(value - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}
function shortId(value: string): string { return value.length > 12 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value; }
function humanize(value: string): string { return value.replace(/^rovenfall:/, '').replaceAll('_', ' '); }
function errorMessage(reason: unknown, locale: Locale): string {
  if (reason instanceof ApiError) {
    if (reason.status === 401) return locale === 'ko' ? '토큰이 올바르지 않습니다.' : 'The token is not valid.';
    if (reason.code === 'TARGET_OFFLINE') return locale === 'ko' ? '경제 거래를 되돌리려면 대상 사용자가 온라인이어야 합니다.' : 'The target player must be online to reverse an economy transaction.';
    return `${reason.message} (${reason.code})`;
  }
  return locale === 'ko' ? '서버 브리지에 연결할 수 없습니다. 서버 실행 상태와 포트를 확인하세요.' : 'Cannot reach the server bridge. Check the server and port.';
}
