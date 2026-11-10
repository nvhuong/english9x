import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../api/useApi';
import { ctxKey, useContextStore } from '../../../app/stores/context';
import { isRunning, type ImportDetail } from './types';

/** One import and its errors. Asks again every 1.5 s while the server is still working on it, and stops by itself when it is finished. */
export function useImportJob(jobId: string | null) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'cms', 'import', jobId ?? 'none'),
    enabled: Boolean(jobId),
    refetchInterval: (q) => (isRunning((q.state.data as ImportDetail | undefined)?.job?.status) || q.state.data === undefined ? 1500 : false),
    queryFn: async () => (await api.GET('/api/cms/import/{jobId}', { params: { path: { jobId: jobId! } } })).data as ImportDetail,
  });
}

/** Submits every draft of a finished import for review as one bundle (a bundle exists only from here on). */
export function useSubmitImport() {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  return useMutation({
    mutationFn: async (jobId: string) => (await api.POST('/api/cms/import/{jobId}/submit', { params: { path: { jobId } } })).data as { bundleId: string },
    onSuccess: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, 'cms', 'import') }),
  });
}

/** Saves a file the server makes (the template, the marked workbook): the request carries the sign-in, so a plain link would not do. */
export function useDownload() {
  const api = useApi();
  return {
    template: async (typeCode: string) => {
      const res = await api.GET('/api/cms/import/templates/{typeCode}.xlsx', { params: { path: { typeCode } }, parseAs: 'blob' });
      save(res.data as Blob, `${typeCode}.xlsx`);
    },
    report: async (jobId: string, name: string) => {
      const res = await api.GET('/api/cms/import/{jobId}/report.xlsx', { params: { path: { jobId } }, parseAs: 'blob' });
      save(res.data as Blob, name);
    },
  };
}

function save(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
