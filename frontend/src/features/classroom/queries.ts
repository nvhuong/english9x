import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../api/useApi';
import type { components } from '../../api/generated/client';
import { ctxKey, useContextStore, useCtxKey } from '../../app/stores/context';

export type ClassView = components['schemas']['ClassView'];
export type ClassMemberView = components['schemas']['ClassMemberView'];
export type AssignmentView = components['schemas']['AssignmentView'];
export type AssignmentReport = components['schemas']['AssignmentReport'];
export type MyAssignment = components['schemas']['MyAssignment'];
export type AssignClassCoursesResponse = components['schemas']['AssignClassCoursesResponse'];


export function useClasses(orgId: string) {
  const api = useApi();
  return useQuery({
    queryKey: useCtxKey('classroom', orgId, 'classes'),
    retry: false,
    queryFn: async () => (await api.GET('/api/orgs/{id}/classes', { params: { path: { id: orgId } } })).data as ClassView[],
  });
}

export function useClass(orgId: string, classId: string) {
  const api = useApi();
  return useQuery({
    queryKey: useCtxKey('classroom', orgId, 'class', classId),
    retry: false,
    queryFn: async () => (await api.GET('/api/orgs/{id}/classes/{cid}', { params: { path: { id: orgId, cid: classId } } })).data as ClassView,
  });
}

export function useCreateClass(orgId: string) {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  const keyParts = [orgId, 'classes'] as const;
  return useMutation({
    mutationFn: async (v: { name: string; teacherId?: string }) =>
      (await api.POST('/api/orgs/{id}/classes', { params: { path: { id: orgId } }, body: v })).data as ClassView,
    onSuccess: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, 'classroom', ...keyParts) }),
  });
}

export function useClassMembers(orgId: string, classId: string) {
  const api = useApi();
  return useQuery({
    queryKey: useCtxKey('classroom', orgId, 'class', classId, 'members'),
    retry: false,
    queryFn: async () => (await api.GET('/api/orgs/{id}/classes/{cid}/members', { params: { path: { id: orgId, cid: classId } } })).data as ClassMemberView[],
  });
}

export function useAddClassMembers(orgId: string, classId: string) {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  const baseParts = [orgId, 'class', classId] as const;
  return useMutation({
    mutationFn: async (userIds: string[]) =>
      (await api.POST('/api/orgs/{id}/classes/{cid}/members', { params: { path: { id: orgId, cid: classId } }, body: { userIds } })).data!,
    onSuccess: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, 'classroom', ...baseParts) }),
  });
}

export function useRemoveClassMember(orgId: string, classId: string) {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  const baseParts = [orgId, 'class', classId] as const;
  return useMutation({
    mutationFn: async (userId: string) => {
      await api.DELETE('/api/orgs/{id}/classes/{cid}/members/{userId}', { params: { path: { id: orgId, cid: classId, userId } } });
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, 'classroom', ...baseParts) }),
  });
}

export function useAssignClassCourses(orgId: string, classId: string) {
  const api = useApi();
  return useMutation({
    mutationFn: async (v: { courseIds: string[]; licenseId: string }) =>
      (await api.POST('/api/orgs/{id}/classes/{cid}/courses', { params: { path: { id: orgId, cid: classId } }, body: v })).data as AssignClassCoursesResponse,
  });
}

export function useClassAssignments(orgId: string, classId: string) {
  const api = useApi();
  return useQuery({
    queryKey: useCtxKey('classroom', orgId, 'class', classId, 'assignments'),
    retry: false,
    queryFn: async () => (await api.GET('/api/orgs/{id}/classes/{cid}/assignments', { params: { path: { id: orgId, cid: classId } } })).data as AssignmentView[],
  });
}

export function useCreateAssignment(orgId: string, classId: string) {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  const keyParts = [orgId, 'class', classId, 'assignments'] as const;
  return useMutation({
    mutationFn: async (v: { lessonId?: string; testVersionId?: string; dueAt: string }) =>
      (await api.POST('/api/orgs/{id}/classes/{cid}/assignments', { params: { path: { id: orgId, cid: classId } }, body: v })).data as AssignmentView,
    onSuccess: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, 'classroom', ...keyParts) }),
  });
}

export function useAssignmentReport(orgId: string, assignmentId: string, enabled: boolean) {
  const api = useApi();
  return useQuery({
    queryKey: useCtxKey('classroom', orgId, 'assignment', assignmentId),
    enabled,
    retry: false,
    queryFn: async () => (await api.GET('/api/orgs/{id}/assignments/{aid}', { params: { path: { id: orgId, aid: assignmentId } } })).data as AssignmentReport,
  });
}

/** The learner's own assignments in the organisation of the current context. */
export function useMyAssignments() {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'classroom', 'me', 'assignments'),
    retry: false,
    queryFn: async () => (await api.GET('/api/me/assignments')).data as MyAssignment[],
  });
}
