import { z } from 'zod';

/** zod messages are i18n keys of the `auth` namespace; pages translate them with `t(message)`. */
const utf8Bytes = (s: string) => new TextEncoder().encode(s).length;

export const emailField = z.string().trim().min(1, 'validation.required').pipe(z.email('validation.email'));

export const passwordField = z
  .string()
  .min(8, 'validation.passwordShort')
  .refine((v) => utf8Bytes(v) <= 72, 'validation.passwordLong'); // bcrypt only reads 72 bytes; the server refuses more

export const loginSchema = z.object({
  email: emailField,
  password: z.string().min(1, 'validation.required'),
});
export type LoginValues = z.infer<typeof loginSchema>;

export const registerSchema = z.object({
  email: emailField,
  displayName: z.string().trim().min(1, 'validation.required').max(100, 'validation.nameLong'),
  password: passwordField,
  consent: z.boolean().refine((v) => v === true, 'validation.consent'),
});
export type RegisterValues = z.infer<typeof registerSchema>;

export const googleConsentSchema = z.object({ consent: z.boolean().refine((v) => v === true, 'validation.consentGoogle') });
export type GoogleConsentValues = z.infer<typeof googleConsentSchema>;

export const forgotSchema = z.object({ email: emailField });
export type ForgotValues = z.infer<typeof forgotSchema>;

export const resetSchema = z
  .object({ newPassword: passwordField, confirm: z.string().min(1, 'validation.required') })
  .refine((v) => v.newPassword === v.confirm, { path: ['confirm'], message: 'validation.passwordMismatch' });
export type ResetValues = z.infer<typeof resetSchema>;

export const resendSchema = z.object({ email: emailField });
export type ResendValues = z.infer<typeof resendSchema>;
