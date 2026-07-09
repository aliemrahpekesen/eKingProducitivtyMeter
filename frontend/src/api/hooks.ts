// TanStack Query hooks over the /api/v1 read surface. Queries are disabled until a tenant is set,
// and never retry (a 401 for a missing/invalid tenant should surface immediately, not spin).
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { apiGet } from './client';
import type { FrictionSummaryView, PageViewConnectorView, SessionView } from './types';

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
