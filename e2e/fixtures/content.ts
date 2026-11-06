import { notImplemented } from './notImplemented';

/** Creates and publishes a question through the CMS API. Needs the content/CMS modules. */
export async function publishedQuestion(_form: string): Promise<{ questionId: string }> {
  return notImplemented('content.publishedQuestion', 'P2.3 (questions)');
}
