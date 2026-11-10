import type { components } from '../../../api/generated/client';

export type MediaView = components['schemas']['MediaView'];
export type MediaLimits = components['schemas']['MediaLimits'];
export type MediaLink = components['schemas']['MediaLink'];
export type MediaKind = NonNullable<MediaView['kind']>;
