/**
 * Personal-data rights (spec §15, Nghị định 13/2023): the audited erasure path a SYS_ADMIN runs. Its own module so it may depend on both the organisation's
 * authorization ({@code org.api}) and the object store ({@code content.media.api}) without the {@code org} module depending on {@code content} (a cycle).
 */
package com.english9x.privacy;
