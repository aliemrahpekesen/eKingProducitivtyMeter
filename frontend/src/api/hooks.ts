// TanStack Query hooks over the /api/v1 read surface. Queries are disabled until a tenant is set,
// and never retry (a 401 for a missing/invalid tenant should surface immediately, not spin).
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { apiGet } from './client';
import type {
  FrictionEvidenceView,
  FrictionSummaryView,
  PageViewConnectorView,
  SessionView,
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
