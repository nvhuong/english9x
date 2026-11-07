import { z } from 'zod';

/** zod messages are i18n keys of the `org` namespace; pages translate them with `t(message)`. */
export const inviteSchema = z.object({
  email: z.string().trim().min(1, 'validation.required').pipe(z.email('validation.email')),
  roles: z.array(z.string()).min(1, 'validation.roleRequired'),
});
export type InviteValues = z.infer<typeof inviteSchema>;

export const createOrgSchema = z.object({
  name: z.string().trim().min(1, 'validation.orgName').max(120, 'validation.nameLong'),
  type: z.enum(['CENTER', 'ENTERPRISE'], { error: 'validation.orgType' }),
});
export type CreateOrgValues = z.infer<typeof createOrgSchema>;
