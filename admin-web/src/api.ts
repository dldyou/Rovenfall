export type ViewId = 'dashboard' | 'players' | 'audit' | 'transactions' | 'claims' | 'shops';
export type SearchScope = 'players' | 'transactions' | 'claims' | 'shops';

export interface DashboardData {
  generatedAt: number;
  serverTimeZone: string;
  writable: boolean;
  onlinePlayers: number;
  knownPlayers: number;
  recentTransactions: number;
  recentVolume: string;
  claims: number;
  shops: number;
  recentAlerts: number;
  recentDenied: number;
  recentAudit: AuditRow[];
}

export interface PageData<T extends DataRow = DataRow> {
  page: number;
  pageSize: number;
  totalPages: number;
  totalEntries: number;
  entries: T[];
}

export interface BaseRow {
  type: string;
  [key: string]: string | number | boolean | undefined;
}

export interface PlayerRow extends BaseRow {
  type: 'player';
  playerId: string;
  name: string;
  role: string;
  firstSeen: number;
  lastSeen: number;
  balance: string;
  activityExperience: string;
  activeCareer: string;
  learnedCareers: number;
  claims: number;
}

export interface AuditRow extends BaseRow {
  type: 'audit';
  timestamp: number;
  actorId: string;
  actorName: string;
  action: string;
  target: string;
  dimension: string;
  position: string;
  before: string;
  after: string;
  reason: string;
  transactionId: string;
  outcome: 'success' | 'denied' | 'failed' | 'no_change';
}

export interface TransactionRow extends BaseRow {
  type: 'transaction';
  transactionId: string;
  timestamp: number;
  actorId: string;
  actorName: string;
  playerId: string;
  playerName: string;
  kind: string;
  amount: string;
  claim: string;
  shopId: string;
  offerId: string;
  item: string;
  quantity: number;
  originalTransactionId: string;
  reversedBy: string;
  invalidatedByRestore: string;
  compensation: string;
}

export interface ClaimRow extends BaseRow {
  type: 'claim';
  key: string;
  dimension: string;
  chunkX: number;
  chunkZ: number;
  ownerId: string;
  ownerName: string;
  purchasePrice: string;
  trustedPlayers: number;
  pendingTransferTo: string;
}

export interface ShopRow extends BaseRow {
  type: 'shop';
  shopId: string;
  templateId: string;
  dimension: string;
  position: string;
  maxDistance: number;
  offers: number;
}

export type DataRow = PlayerRow | AuditRow | TransactionRow | ClaimRow | ShopRow | BaseRow;

export interface PlayerDetail {
  playerId: string;
  name: string;
  online: boolean;
  role: string;
  firstSeen: number;
  lastSeen: number;
  balance: string;
  activityExperience: string;
  activeCareer: string;
  learnedCareers: number;
  claimCount: number;
  transactions: TransactionRow[];
  claims: ClaimRow[];
  audit: AuditRow[];
}

export interface OperationResult {
  ok: boolean;
  status: string;
  transactionId: string;
  details: Record<string, string | number | boolean>;
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code = 'REQUEST_FAILED',
  ) {
    super(message);
  }
}

export async function apiRequest<T>(token: string, path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  headers.set('Accept', 'application/json');
  headers.set('Authorization', `Bearer ${token}`);
  if (init?.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(path, {
    ...init,
    headers,
  });
  const payload = await response.json().catch(() => null) as
    | T
    | { error?: { code?: string; message?: string } }
    | null;
  if (!response.ok) {
    const error = payload && typeof payload === 'object' && 'error' in payload
      ? (payload as { error?: { code?: string; message?: string } }).error
      : undefined;
    throw new ApiError(error?.message ?? `HTTP ${response.status}`, response.status, error?.code);
  }
  return payload as T;
}

export function queryString(values: Record<string, string | number | undefined>): string {
  const params = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined && String(value).length > 0) params.set(key, String(value));
  });
  return params.toString();
}

interface ModelContext {
  registerTool(
    tool: {
      name: string;
      title?: string;
      description: string;
      inputSchema: object;
      annotations?: { readOnlyHint?: boolean; untrustedContentHint?: boolean };
      execute(input: unknown): unknown;
    },
    options?: { signal?: AbortSignal },
  ): void | Promise<void>;
}

declare global {
  interface Document {
    readonly modelContext?: ModelContext;
  }
}

export function registerReadOnlyTools(token: string, report: (error: unknown) => void): () => void {
  const context = typeof document === 'undefined' ? undefined : document.modelContext;
  if (!context?.registerTool || !token) return () => undefined;
  const lifecycle = new AbortController();
  const options = { signal: lifecycle.signal };
  const registration = [
    context.registerTool({
      name: 'search_rovenfall_players',
      title: 'Search Rovenfall players',
      description: 'Search the connected Rovenfall server player directory by nickname or UUID.',
      inputSchema: {
        type: 'object',
        properties: {
          query: { type: 'string', minLength: 1, maxLength: 128 },
          page: { type: 'integer', minimum: 0, default: 0 },
        },
        required: ['query'],
        additionalProperties: false,
      },
      annotations: { readOnlyHint: true, untrustedContentHint: true },
      async execute(input) {
        const value = input as { query?: unknown; page?: unknown };
        if (typeof value?.query !== 'string' || value.query.length < 1 || value.query.length > 128) {
          throw new Error('query must contain 1–128 characters');
        }
        const page = Number.isInteger(value.page) && Number(value.page) >= 0 ? Number(value.page) : 0;
        return apiRequest<PageData>(token, `/api/v1/search?${queryString({
          scope: 'players', query: value.query, page, pageSize: 25,
        })}`);
      },
    }, options),
    context.registerTool({
      name: 'query_rovenfall_audit',
      title: 'Query Rovenfall audit log',
      description: 'Read bounded Rovenfall audit records, optionally filtered by player and action.',
      inputSchema: {
        type: 'object',
        properties: {
          query: { type: 'string', maxLength: 128 },
          player: { type: 'string', maxLength: 128 },
          action: { type: 'string', maxLength: 128 },
          page: { type: 'integer', minimum: 0, default: 0 },
        },
        additionalProperties: false,
      },
      annotations: { readOnlyHint: true, untrustedContentHint: true },
      async execute(input) {
        const value = (input ?? {}) as Record<string, unknown>;
        for (const field of ['query', 'player', 'action']) {
          if (value[field] !== undefined && (typeof value[field] !== 'string' || String(value[field]).length > 128)) {
            throw new Error(`${field} must be a string of at most 128 characters`);
          }
        }
        const page = Number.isInteger(value.page) && Number(value.page) >= 0 ? Number(value.page) : 0;
        return apiRequest<PageData<AuditRow>>(token, `/api/v1/audit?${queryString({
          query: value.query as string | undefined,
          player: value.player as string | undefined,
          action: value.action as string | undefined,
          page,
          pageSize: 25,
        })}`);
      },
    }, options),
  ];
  registration.forEach(value => void Promise.resolve(value).catch(report));
  return () => lifecycle.abort();
}
