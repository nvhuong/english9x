import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../api/useApi';
import type { components } from '../../api/generated/client';
import { ctxKey, useContextStore, useCtxKey } from '../../app/stores/context';

export type ClassView = components['schemas']['ClassView'];
export type ClassMemberView = components['schemas']['ClassMemberView'];
export type AssignmentView = components['schemas']['AssignmentView'];
export type AssignmentReport = components['schemas']['AssignmentReport'];
export type MyAssignment = components['schemas']['MyAssignment'];
export type SessionView = components['schemas']['SessionView'];
export type ScheduleItem = components['schemas']['ScheduleItem'];
export type AttendanceStatus = components['schemas']['AttendanceStatus'];
export type AttendanceSheet = components['schemas']['AttendanceSheet'];
export type AttendanceReport = components['schemas']['AttendanceReport'];
export type AttendanceMark = components['schemas']['AttendanceMark'];
export type ClassReport = components['schemas']['ClassReport'];
export type ReportLearner = components['schemas']['ReportLearner'];
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

export function useClassSessions(orgId: string, classId: string) {
  const api = useApi();
  return useQuery({
    queryKey: useCtxKey('classroom', orgId, 'class', classId, 'sessions'),
    retry: false,
    queryFn: async () => (await api.GET('/api/orgs/{id}/classes/{cid}/sessions', { params: { path: { id: orgId, cid: classId } } })).data as SessionView[],
  });
}

export function useCreateSession(orgId: string, classId: string) {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  const keyParts = [orgId, 'class', classId, 'sessions'] as const;
  return useMutation({
    mutationFn: async (v: { mode: 'OFFLINE' | 'ONLINE'; startsAt: string; endsAt: string; room?: string; meetingUrl?: string }) =>
      (await api.POST('/api/orgs/{id}/classes/{cid}/sessions', { params: { path: { id: orgId, cid: classId } }, body: v })).data as SessionView,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ctxKey(ctx, 'classroom', ...keyParts) });
      void qc.invalidateQueries({ queryKey: ctxKey(ctx, 'classroom', 'me', 'schedule') });
    },
  });
}

/** The person's own sessions (as a learner or as a teacher) starting in [from, to) in the organisation of the current context. */
export function useMySchedule(from: string, to: string) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'classroom', 'me', 'schedule', from, to),
    retry: false,
    queryFn: async () => (await api.GET('/api/me/schedule', { params: { query: { from, to } } })).data as ScheduleItem[],
  });
}

export function useAttendanceSheet(orgId: string, classId: string, sessionId: string, enabled: boolean) {
  const api = useApi();
  return useQuery({
    queryKey: useCtxKey('classroom', orgId, 'class', classId, 'attendance', sessionId),
    enabled,
    retry: false,
    queryFn: async () =>
      (await api.GET('/api/orgs/{id}/classes/{cid}/sessions/{sid}/attendance', { params: { path: { id: orgId, cid: classId, sid: sessionId } } })).data as AttendanceSheet,
  });
}

export function useMarkAttendance(orgId: string, classId: string, sessionId: string) {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  return useMutation({
    mutationFn: async (marks: AttendanceMark[]) =>
      (await api.PUT('/api/orgs/{id}/classes/{cid}/sessions/{sid}/attendance', { params: { path: { id: orgId, cid: classId, sid: sessionId } }, body: marks })).data as AttendanceSheet,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ctxKey(ctx, 'classroom', orgId, 'class', classId, 'attendance') });
      void qc.invalidateQueries({ queryKey: ctxKey(ctx, 'classroom', 'me', 'schedule') });
    },
  });
}

export function useAttendanceReport(orgId: string, classId: string) {
  const api = useApi();
  return useQuery({
    queryKey: useCtxKey('classroom', orgId, 'class', classId, 'attendance'),
    retry: false,
    queryFn: async () => (await api.GET('/api/orgs/{id}/classes/{cid}/attendance', { params: { path: { id: orgId, cid: classId } } })).data as AttendanceReport,
  });
}

/** The report of a class (F49): one row per learner. */
export function useClassReport(orgId: string, classId: string) {
  const api = useApi();
  return useQuery({
    queryKey: useCtxKey('classroom', orgId, 'class', classId, 'report'),
    retry: false,
    queryFn: async () => (await api.GET('/api/orgs/{id}/classes/{cid}/report', { params: { path: { id: orgId, cid: classId } } })).data as ClassReport,
  });
}

/** Saves the Excel file of the report: the request carries the sign-in, so a plain link would not do. */
export function useDownloadClassReport(orgId: string, classId: string) {
  const api = useApi();
  return async (lang: string, filename: string) => {
    const res = await api.GET('/api/orgs/{id}/classes/{cid}/report.xlsx', { params: { path: { id: orgId, cid: classId }, query: { lang } }, parseAs: 'blob' });
    const url = URL.createObjectURL(res.data as unknown as Blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  };
}
