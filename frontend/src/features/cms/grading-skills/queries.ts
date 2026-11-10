import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../api/useApi';
import { ctxKey, useContextStore } from '../../../app/stores/context';
import type { SkillDraft, SkillFilters, SkillPage, SkillVersion } from './types';

export function useSkillList(filters: SkillFilters) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'cms', 'grading-skills', 'list', filters),
    queryFn: async () =>
      (await api.GET('/api/cms/grading-skills', { params: { query: { exam: filters.exam, status: filters.status, page: filters.page } } })).data as unknown as SkillPage,
  });
}

export function useSkill(skillId: string | undefined) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'cms', 'grading-skills', 'one', skillId ?? 'none'),
    enabled: Boolean(skillId),
    queryFn: async () => (await api.GET('/api/cms/grading-skills/{id}', { params: { path: { id: skillId! } } })).data as unknown as SkillVersion,
  });
}

export function useSkillMutations() {
  const api = useApi();
  const queryClient = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  const drop = () => queryClient.invalidateQueries({ queryKey: ctxKey(ctx, 'cms', 'grading-skills') });
  return {
    create: useMutation({
      mutationFn: async (draft: SkillDraft) => (await api.POST('/api/cms/grading-skills', { body: draft as never })).data as unknown as SkillVersion,
      onSuccess: drop,
    }),
    save: useMutation({
      mutationFn: async ({ skillId, draft }: { skillId: string; draft: SkillDraft }) => {
        const r = await api.PUT('/api/cms/grading-skills/{id}', { params: { path: { id: skillId } }, body: draft as never });
        return { version: r.data as unknown as SkillVersion, newVersion: r.response.status === 201 };
      },
      onSuccess: drop,
    }),
    submit: useMutation({
      mutationFn: async (skillId: string) => (await api.POST('/api/cms/grading-skills/{id}/submit', { params: { path: { id: skillId } } })).data,
      onSuccess: drop,
    }),
    /** Runs the skill on a sample answer without saving a score. Until the AI grader exists the server answers 501 (dry-run-unavailable). */
    dryRun: useMutation({
      mutationFn: async ({ skillId, response }: { skillId: string; response: string }) =>
        (await api.POST('/api/cms/grading-skills/{id}/dry-run', { params: { path: { id: skillId } }, body: { response } })).data,
    }),
  };
}
