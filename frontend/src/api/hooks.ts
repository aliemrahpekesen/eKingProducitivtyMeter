// TanStack Query hooks over the /api/v1 read surface. Queries are disabled until a tenant is set,
// and never retry (a 401 for a missing/invalid tenant should surface immediately, not spin).
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiGet, apiPost } from './client';
import type {
  ConnectorAdminView,
  ConnectorTypeView,
  FrictionEvidenceView,
  FrictionSummaryView,
  OrganizationView,
  PageViewConnectorView,
  PageViewReportView,
  ReportDocumentView,
  ReportView,
  SampleDataResult,
  SessionView,
  TeamInFlightView,
  TeamRecommendationsView,
  TenantView,
  TestConnectionResult,
  TrendsView,
} from './types';

const DEFAULT_PAGE_SIZE = 20;

export function useSession(tenantId: string) {
  return useQuery({
    queryKey: ['session', tenantId],
    queryFn: () => apiGet<SessionView>('/api/v1/session', tenantId),
    enabled: tenantId.length > 0,
    retry: false,
  });
}

export function useConnectors(tenantId: string, pageSize: number = DEFAULT_PAGE_SIZE) {
  return useInfiniteQuery({
    queryKey: ['connectors', tenantId, pageSize],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: String(pageSize) });
      if (pageParam) {
        params.set('cursor', pageParam);
      }
      return apiGet<PageViewConnectorView>(`/api/v1/connectors?${params.toString()}`, tenantId);
    },
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) =>
      lastPage.hasMore ? (lastPage.nextCursor ?? undefined) : undefined,
    enabled: tenantId.length > 0,
    retry: false,
  });
}

export function useFrictionSummary(tenantId: string) {
  return useQuery({
    queryKey: ['friction', tenantId],
    queryFn: () => apiGet<FrictionSummaryView>('/api/v1/friction/summary', tenantId),
    enabled: tenantId.length > 0,
    retry: false,
  });
}

// Drill-to-evidence for one team; only fetched when `enabled` (i.e. the drawer is open).
export function useFrictionEvidence(tenantId: string, teamId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['friction-evidence', tenantId, teamId],
    queryFn: () =>
      apiGet<FrictionEvidenceView>(
        `/api/v1/friction/teams/${encodeURIComponent(teamId)}/evidence`,
        tenantId,
      ),
    enabled: enabled && tenantId.length > 0 && teamId.length > 0,
    retry: false,
  });
}

// ── M3 dashboard: trends, in-flight, recommendations ───────────────────────────────────────────────

export function useMetricTrends(tenantId: string, weeks: number) {
  return useQuery({
    queryKey: ['metrics', 'trends', tenantId, weeks],
    queryFn: () => apiGet<TrendsView>(`/api/v1/metrics/trends?weeks=${weeks}`, tenantId),
    enabled: tenantId.length > 0,
    retry: false,
  });
}

export function useInFlight(tenantId: string) {
  return useQuery({
    queryKey: ['metrics', 'in-flight', tenantId],
    queryFn: () => apiGet<TeamInFlightView[]>('/api/v1/metrics/in-flight', tenantId),
    enabled: tenantId.length > 0,
    retry: false,
  });
}

export function useRecommendations(tenantId: string) {
  return useQuery({
    queryKey: ['insights', 'recommendations', tenantId],
    queryFn: () => apiGet<TeamRecommendationsView[]>('/api/v1/insights/recommendations', tenantId),
    enabled: tenantId.length > 0,
    retry: false,
  });
}

// ── M1 admin panel ──────────────────────────────────────────────────────────────────────────────

export function useTenants() {
  return useQuery({
    queryKey: ['admin', 'tenants'],
    queryFn: () => apiGet<TenantView[]>('/api/v1/admin/tenants', ''),
    retry: false,
  });
}

export function useStructure(tenantId: string) {
  return useQuery({
    queryKey: ['admin', 'structure', tenantId],
    queryFn: () => apiGet<OrganizationView[]>('/api/v1/admin/structure', tenantId),
    enabled: tenantId.length > 0,
    retry: false,
  });
}

export function useConnectorTypes() {
  return useQuery({
    queryKey: ['admin', 'connector-types'],
    queryFn: () => apiGet<ConnectorTypeView[]>('/api/v1/admin/connector-types', ''),
    retry: false,
  });
}

export function useAdminConnectors(tenantId: string) {
  return useQuery({
    queryKey: ['admin', 'connectors', tenantId],
    queryFn: () => apiGet<ConnectorAdminView[]>('/api/v1/admin/connectors', tenantId),
    enabled: tenantId.length > 0,
    retry: false,
  });
}

export function useCreateTenant() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { name: string; slug: string }) =>
      apiPost<TenantView>('/api/v1/admin/tenants', '', input),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['admin', 'tenants'] }),
  });
}

export function useRegisterConnector(tenantId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: {
      type: string;
      name: string;
      config: Record<string, string>;
      secret?: string;
    }) => apiPost<ConnectorAdminView>('/api/v1/admin/connectors', tenantId, input),
    onSuccess: () =>
      void queryClient.invalidateQueries({ queryKey: ['admin', 'connectors', tenantId] }),
  });
}

export function useTestConnector(tenantId: string) {
  return useMutation({
    mutationFn: (connectorId: string) =>
      apiPost<TestConnectionResult>(`/api/v1/admin/connectors/${connectorId}/test`, tenantId),
  });
}

export function useSetConnectorStatus(tenantId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { connectorId: string; status: 'ACTIVE' | 'DISABLED' }) =>
      apiPost<ConnectorAdminView>(
        `/api/v1/admin/connectors/${input.connectorId}/status`,
        tenantId,
        { status: input.status },
      ),
    onSuccess: () =>
      void queryClient.invalidateQueries({ queryKey: ['admin', 'connectors', tenantId] }),
  });
}

export function useSyncConnector(tenantId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (connectorId: string) =>
      apiPost<SampleDataResult>(`/api/v1/admin/connectors/${connectorId}/sync`, tenantId),
    onSuccess: () => void queryClient.invalidateQueries(),
  });
}

export function useLoadSampleData(tenantId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => apiPost<SampleDataResult>('/api/v1/admin/sample-data', tenantId),
    onSuccess: () => void queryClient.invalidateQueries(),
  });
}

// ── M4 deterministic reports (TASK-0022) ───────────────────────────────────────────────────────

export function useReports(tenantId: string, pageSize: number = DEFAULT_PAGE_SIZE) {
  return useInfiniteQuery({
    queryKey: ['reports', tenantId, pageSize],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: String(pageSize) });
      if (pageParam) {
        params.set('cursor', pageParam);
      }
      return apiGet<PageViewReportView>(`/api/v1/reports?${params.toString()}`, tenantId);
    },
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) =>
      lastPage.hasMore ? (lastPage.nextCursor ?? undefined) : undefined,
    enabled: tenantId.length > 0,
    retry: false,
  });
}

export function useReport(tenantId: string, reportId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['reports', 'detail', tenantId, reportId],
    queryFn: () =>
      apiGet<ReportDocumentView>(`/api/v1/reports/${encodeURIComponent(reportId)}`, tenantId),
    enabled: enabled && tenantId.length > 0 && reportId.length > 0,
    retry: false,
  });
}

export function useGenerateReport(tenantId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { weeks: number }) =>
      apiPost<ReportView>('/api/v1/reports', tenantId, {
        type: 'EXEC_SUMMARY',
        weeks: input.weeks,
      }),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['reports'] }),
  });
}
